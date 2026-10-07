package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import pt.diamondcars.catalogbackend.config.support.FakeAuth0;

/**
 * {@link Auth0JwkSource} behind the production decoder, against an Auth0 tenant in the test JVM
 * that can hang (TASK-002, review r1, IMPORTANTE 1). What is measured here is what keeps the site up
 * when Auth0 is slow: how many requests can be held, for how long, and how many times Auth0 is
 * called.
 *
 * <p>Outcome classes: {@link BadJwtException} is a 401 (token invalid); any other {@link
 * JwtException} is a 500 (keys unavailable, the token may be valid). Most tests use a fake clock for
 * the 10 s interval and the 5 min key life, so nothing here sleeps through them.
 */
@ExtendWith(OutputCaptureExtension.class)
class Auth0JwkSourceTest {

	private static final Duration FAST = Duration.ofMillis(500);

	private final AtomicLong clock = new AtomicLong(1_000_000L);
	private final ExecutorService pool = Executors.newFixedThreadPool(100);
	private FakeAuth0 auth0;

	@BeforeEach
	void startTenant() {
		auth0 = FakeAuth0.start();
	}

	@AfterEach
	void stop() {
		pool.shutdownNow();
		auth0.close();
	}

	/**
	 * The scenario of review r1: Auth0 accepts connections and never answers, before the keys were
	 * ever fetched, and many requests arrive at once. One request fetches and gives up at the 3 s read
	 * timeout, at most {@link Auth0JwkSource#MAX_WAITING_REQUESTS} wait for it, every other one gets
	 * its 500 immediately; Auth0 is called once. Before the fix they queued for 3 s each.
	 */
	@Test
	void hungAuth0BeforeTheFirstFetchHoldsOneFetchAndAFewWaiters() {
		JwtDecoder decoder = productionDecoder();
		String token = auth0.token(List.of("admin"));
		auth0.hang();

		List<Outcome> outcomes = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> decodeConcurrently(decoder, token, 60));

		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNotNull().isNotInstanceOf(BadJwtException.class));
		long held = outcomes.stream().filter(outcome -> outcome.took().compareTo(Duration.ofSeconds(1)) > 0).count();
		assertThat(held).isBetween(1L, 1L + Auth0JwkSource.MAX_WAITING_REQUESTS);
		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.took()).isLessThan(Duration.ofSeconds(8)));
		assertThat(auth0.jwksRequests()).isEqualTo(1);

		Outcome next = decodeTimed(decoder, token);
		assertThat(next.error()).isNotNull().isNotInstanceOf(BadJwtException.class);
		assertThat(next.took()).isLessThan(FAST);
		assertThat(auth0.jwksRequests()).as("negative cache: no new call inside the interval").isEqualTo(1);
	}

	@Test
	void auth0BackAfterAnOutageIsUsedOnceTheIntervalHasPassed() {
		JwtDecoder decoder = decoderWithFakeClock(Duration.ofMillis(300));
		String token = auth0.token(List.of("admin"));
		auth0.hang();
		assertThatThrownBy(() -> decoder.decode(token)).isNotInstanceOf(BadJwtException.class).isInstanceOf(JwtException.class);
		auth0.resume();

		assertThatThrownBy(() -> decoder.decode(token)).isNotInstanceOf(BadJwtException.class).isInstanceOf(JwtException.class);
		assertThat(auth0.jwksRequests()).isEqualTo(1);

		clock.addAndGet(Auth0JwkSource.MIN_FETCH_INTERVAL.toNanos());
		assertThat(decoder.decode(token).getSubject()).isEqualTo("auth0|tester");
		assertThat(auth0.jwksRequests()).isEqualTo(2);
	}

	/** Review r1 measured 60 JWKS fetches for 60 tokens with an unknown {@code kid}. */
	@Test
	void unknownKidsAreRejectedWithoutAFetchPerToken() {
		JwtDecoder decoder = decoderWithFakeClock(FAST);
		decoder.decode(auth0.token(List.of("admin")));
		String invented = auth0.token(FakeAuth0.newKey("invented"), claims -> {});

		List<Outcome> outcomes = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> decodeConcurrently(decoder, invented, 100));

		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isInstanceOf(BadJwtException.class));
		assertThat(auth0.jwksRequests()).isEqualTo(1);

		clock.addAndGet(Auth0JwkSource.MIN_FETCH_INTERVAL.toNanos());
		for (int i = 0; i < 50; i++) {
			assertThatThrownBy(() -> decoder.decode(invented)).isInstanceOf(BadJwtException.class);
		}
		assertThat(auth0.jwksRequests()).as("one refresh per interval").isEqualTo(2);
	}

	/**
	 * Review r1: with the JWKS hung, an admin with a valid token waited 14.5 s behind requests with
	 * invented {@code kid}s. Known keys are now answered from memory, without any lock.
	 */
	@Test
	void validTokensAreAnsweredFromMemoryWhileARefreshHangs() throws Exception {
		JwtDecoder decoder = decoderWithFakeClock(Duration.ofSeconds(2));
		String valid = auth0.token(List.of("admin"));
		decoder.decode(valid);
		auth0.hang();
		clock.addAndGet(Auth0JwkSource.MIN_FETCH_INTERVAL.toNanos());
		String invented = auth0.token(FakeAuth0.newKey("invented"), claims -> {});
		Future<Outcome> refreshing = pool.submit(() -> decodeTimed(decoder, invented));
		awaitUntil(() -> auth0.jwksRequests() == 2);

		Outcome admin = decodeTimed(decoder, valid);

		assertThat(admin.error()).isNull();
		assertThat(admin.took()).isLessThan(FAST);
		Outcome attacker = refreshing.get();
		assertThat(attacker.error()).as("kid could not be checked: 500, not 401").isNotNull().isNotInstanceOf(BadJwtException.class);
		assertThat(decoder.decode(valid).getSubject()).as("last good keys kept during the outage").isEqualTo("auth0|tester");
	}

	/** A key revoked in Auth0 stops being accepted once the cached keys expire. */
	@Test
	void expiredKeysAreRefreshedInTheBackground() throws Exception {
		JwtDecoder decoder = decoderWithFakeClock(FAST);
		String signedWithK1 = auth0.token(List.of("admin"));
		decoder.decode(signedWithK1);
		RSAKey k2 = FakeAuth0.newKey("k2");
		auth0.publish(k2);
		clock.addAndGet(Auth0JwkSource.KEYS_TIME_TO_LIVE.toNanos());

		assertThat(decoder.decode(signedWithK1).getSubject()).as("served while the refresh runs").isEqualTo("auth0|tester");
		awaitUntil(() -> rejected(decoder, signedWithK1));

		assertThat(decoder.decode(auth0.token(k2, claims -> {})).getSubject()).isEqualTo("auth0|tester");
		assertThat(auth0.jwksRequests()).isEqualTo(2);
	}

	/** Review r1, S1: a flood with Auth0 refusing connections wrote one stack trace per request. */
	@Test
	void eachFailedFetchIsOneWarningLineWithoutStackTrace(CapturedOutput output) throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}
		JwtDecoder decoder =
				SecurityConfig.auth0JwtDecoder(source("http://127.0.0.1:" + closedPort + "/", FAST), auth0.issuer(), FakeAuth0.AUDIENCE);
		String token = auth0.token(List.of("admin"));

		for (int i = 0; i < 50; i++) {
			assertThatThrownBy(() -> decoder.decode(token)).isNotInstanceOf(BadJwtException.class);
		}
		clock.addAndGet(Auth0JwkSource.MIN_FETCH_INTERVAL.toNanos());
		assertThatThrownBy(() -> decoder.decode(token)).isNotInstanceOf(BadJwtException.class);

		assertThat(output.getAll().lines().filter(line -> line.contains("JWKS do Auth0 indisponivel")))
				.hasSize(2)
				.allSatisfy(line -> assertThat(line).contains("WARN").contains("127.0.0.1:" + closedPort));
		assertThat(output.getAll()).doesNotContain("\tat ");
	}

	private JwtDecoder productionDecoder() {
		return new SecurityConfig().jwtDecoder(auth0.issuer(), FakeAuth0.AUDIENCE);
	}

	private JwtDecoder decoderWithFakeClock(Duration timeout) {
		return SecurityConfig.auth0JwtDecoder(source(auth0.issuer(), timeout), auth0.issuer(), FakeAuth0.AUDIENCE);
	}

	private Auth0JwkSource source(String issuer, Duration timeout) {
		int millis = Math.toIntExact(timeout.toMillis());
		return new Auth0JwkSource(
				Auth0JwkSource.jwkSetUrl(issuer),
				new DefaultResourceRetriever(millis, millis, 50 * 1024),
				Auth0JwkSource.MIN_FETCH_INTERVAL,
				Auth0JwkSource.KEYS_TIME_TO_LIVE,
				Auth0JwkSource.MAX_WAITING_REQUESTS,
				timeout.multipliedBy(2),
				clock::get);
	}

	private List<Outcome> decodeConcurrently(JwtDecoder decoder, String token, int requests) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Outcome>> futures = new ArrayList<>();
		for (int i = 0; i < requests; i++) {
			futures.add(
					pool.submit(
							() -> {
								start.await();
								return decodeTimed(decoder, token);
							}));
		}
		start.countDown();
		List<Outcome> outcomes = new ArrayList<>();
		for (Future<Outcome> future : futures) {
			outcomes.add(future.get());
		}
		return outcomes;
	}

	private static Outcome decodeTimed(JwtDecoder decoder, String token) {
		long start = System.nanoTime();
		try {
			decoder.decode(token);
			return new Outcome(null, Duration.ofNanos(System.nanoTime() - start));
		} catch (JwtException e) {
			return new Outcome(e, Duration.ofNanos(System.nanoTime() - start));
		}
	}

	private static boolean rejected(JwtDecoder decoder, String token) {
		try {
			decoder.decode(token);
			return false;
		} catch (BadJwtException e) {
			return true;
		}
	}

	private static void awaitUntil(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("condition not met within 5 s").isLessThan(deadline);
			Thread.sleep(10);
		}
	}

	private record Outcome(JwtException error, Duration took) {}
}
