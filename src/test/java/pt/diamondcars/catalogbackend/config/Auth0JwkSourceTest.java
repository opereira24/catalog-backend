package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
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
 * JwtException} is a 500 (keys unavailable, the token may be valid). Every source here is the
 * production one ({@link Auth0JwkSource#forIssuer}); most tests give it a fake clock for the 10 s
 * interval and the 5 min key life, so nothing here sleeps through them.
 *
 * <p>The production limits are written here as literals, never read from the class (review r2,
 * S-e): a test that advanced the clock by {@code KEYS_TIME_TO_LIVE} passed just as well with a key
 * life of 1000 days. Each limit is checked on both sides of its boundary.
 */
@ExtendWith(OutputCaptureExtension.class)
class Auth0JwkSourceTest {

	private static final Duration FAST = Duration.ofMillis(500);

	/** Documented minimum time between two fetch attempts (negative cache, rate limit). */
	private static final Duration FETCH_INTERVAL = Duration.ofSeconds(10);

	/** Documented key life: a key revoked in Auth0 is accepted for at most this long. */
	private static final Duration KEY_LIFE = Duration.ofMinutes(5);

	/** Documented largest JWKS accepted (Auth0's is about 2 KB). */
	private static final int JWKS_SIZE_LIMIT = 51_200;

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
	 * ever fetched, and many requests arrive at once. One request fetches and gives up at the 3 s
	 * headers timeout, exactly 8 wait for it, every other one gets its 500 immediately; Auth0 is
	 * called once. Before the fix they queued for 3 s each.
	 */
	@Test
	void hungAuth0BeforeTheFirstFetchHoldsOneFetchAndAFewWaiters() {
		JwtDecoder decoder = productionDecoder();
		String token = auth0.token(List.of("admin"));
		auth0.hang();

		List<Outcome> outcomes = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> decodeConcurrently(decoder, token, 60));

		assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.error()).isNotNull().isNotInstanceOf(BadJwtException.class));
		long held = outcomes.stream().filter(outcome -> outcome.took().compareTo(Duration.ofSeconds(1)) > 0).count();
		assertThat(held).as("the request that fetches and the 8 that may wait for it").isEqualTo(9L);
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

		clock.addAndGet(FETCH_INTERVAL.toNanos() - 1);
		assertThatThrownBy(() -> decoder.decode(token)).isNotInstanceOf(BadJwtException.class).isInstanceOf(JwtException.class);
		assertThat(auth0.jwksRequests()).as("1 ns before the interval").isEqualTo(1);

		clock.addAndGet(1);
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

		clock.addAndGet(FETCH_INTERVAL.toNanos());
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
		clock.addAndGet(FETCH_INTERVAL.toNanos());
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

	/** A key revoked in Auth0 stops being accepted once the cached keys are 5 minutes old. */
	@Test
	void expiredKeysAreRefreshedInTheBackground() throws Exception {
		JwtDecoder decoder = decoderWithFakeClock(FAST);
		String signedWithK1 = auth0.token(List.of("admin"));
		decoder.decode(signedWithK1);
		RSAKey k2 = FakeAuth0.newKey("k2");
		auth0.publish(k2);

		clock.addAndGet(KEY_LIFE.toNanos() - 1);
		assertThat(decoder.decode(signedWithK1).getSubject()).isEqualTo("auth0|tester");
		Thread.sleep(200);
		assertThat(auth0.jwksRequests()).as("no refresh 1 ns before the key life").isEqualTo(1);

		clock.addAndGet(1);

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
		clock.addAndGet(FETCH_INTERVAL.toNanos());
		assertThatThrownBy(() -> decoder.decode(token)).isNotInstanceOf(BadJwtException.class);

		assertThat(output.getAll().lines().filter(line -> line.contains("JWKS do Auth0 indisponivel")))
				.hasSize(2)
				.allSatisfy(line -> assertThat(line).contains("WARN").contains("127.0.0.1:" + closedPort));
		assertThat(output.getAll()).doesNotContain("\tat ");
	}

	/**
	 * A JWKS larger than 50 KB is refused while it arrives (500, the keys could not be read); exactly
	 * 50 KB is accepted. Without the limit a hostile or broken Auth0 could make the application read
	 * a response of any size into memory (review r2 measured an 800 MB body cut at 51 200 bytes).
	 */
	@Test
	void jwksOverTheSizeLimitIsRefused(CapturedOutput output) {
		JwtDecoder decoder = decoderWithFakeClock(Duration.ofSeconds(2));
		String token = auth0.token(List.of("admin"));
		auth0.publishJson(paddedJwks(JWKS_SIZE_LIMIT + 1));

		assertThatThrownBy(() -> decoder.decode(token)).isNotInstanceOf(BadJwtException.class).isInstanceOf(JwtException.class);
		assertThat(output.getAll()).contains("JWKS maior do que o limite de " + JWKS_SIZE_LIMIT + " bytes");

		auth0.publishJson(paddedJwks(JWKS_SIZE_LIMIT));
		clock.addAndGet(FETCH_INTERVAL.toNanos());
		assertThat(decoder.decode(token).getSubject()).isEqualTo("auth0|tester");
		assertThat(auth0.jwksRequests()).isEqualTo(2);
	}

	/**
	 * Review r2, S-a: a JWKS sent one byte every 2 s kept a fetch going for more than 25 s, because the
	 * read timeout was per read. Each byte here arrives well inside the 3 s headers timeout; the
	 * production fetch is still cut at its 6 s total and the request gets its 500. Without the total
	 * limit this fetch would take about 100 s (one byte every 0.2 s).
	 */
	@Test
	void jwksSentByteByByteIsCutAtTheFetchDeadline(CapturedOutput output) {
		JwtDecoder decoder = productionDecoder();
		String token = auth0.token(List.of("admin"));
		auth0.drip(Duration.ofMillis(200));

		Outcome outcome = assertTimeoutPreemptively(Duration.ofSeconds(15), () -> decodeTimed(decoder, token));

		assertThat(outcome.error()).isNotNull().isNotInstanceOf(BadJwtException.class);
		assertThat(outcome.took()).isBetween(Duration.ofMillis(5_500), Duration.ofMillis(7_500));
		assertThat(output.getAll()).contains("prazo total de 6000 ms");
		assertThat(auth0.jwksRequests()).isEqualTo(1);
	}

	private String paddedJwks(int bytes) {
		String keys = new JWKSet(auth0.signingKey().toPublicJWK()).toString();
		String padding = "x".repeat(bytes - keys.length() - "\"padding\":\"\",".length());
		String json = "{\"padding\":\"" + padding + "\"," + keys.substring(1);
		assertThat(json).hasSize(bytes);
		return json;
	}

	private JwtDecoder productionDecoder() {
		return new SecurityConfig().jwtDecoder(auth0.issuer(), FakeAuth0.AUDIENCE);
	}

	private JwtDecoder decoderWithFakeClock(Duration timeout) {
		return SecurityConfig.auth0JwtDecoder(source(auth0.issuer(), timeout), auth0.issuer(), FakeAuth0.AUDIENCE);
	}

	private Auth0JwkSource source(String issuer, Duration timeout) {
		return Auth0JwkSource.forIssuer(issuer, timeout, clock::get);
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
