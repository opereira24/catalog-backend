package pt.diamondcars.catalogbackend.config.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * An Auth0 tenant in the test JVM: an HTTP server on a free loopback port that publishes a JWKS at
 * {@code /.well-known/jwks.json} (where Auth0 publishes it) with locally generated RSA keys, and the
 * OIDC discovery document at {@code /.well-known/openid-configuration}, signs RS256 tokens like
 * Auth0 does, counts the requests and can hang them, or send the JWKS one byte at a time, to stand
 * for a slow or hostile Auth0. Nothing leaves the machine.
 *
 * <p>The discovery document is served (and hangs) like the JWKS even though the production decoder
 * never asks for it: a decoder that went back to OIDC discovery must meet the same slow Auth0, not a
 * fast 404 (review r2, S-d).
 */
public final class FakeAuth0 implements AutoCloseable {

	/** The production audience, {@code AUTH0_AUDIENCE}. */
	public static final String AUDIENCE = "https://oteustand.pt/api";

	private final HttpServer server;
	private final ExecutorService executor = Executors.newCachedThreadPool();
	private final AtomicInteger jwksRequests = new AtomicInteger();
	private final AtomicInteger requests = new AtomicInteger();
	private final RSAKey signingKey = newKey("k1");
	private volatile String publishedJson = new JWKSet(signingKey.toPublicJWK()).toString();
	private volatile CountDownLatch hang;
	private volatile Duration drip;

	private FakeAuth0() {
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 100);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		server.setExecutor(executor);
		server.createContext("/", this::serveNotFound);
		server.createContext("/.well-known/jwks.json", this::serveJwks);
		server.createContext("/.well-known/openid-configuration", this::serveDiscovery);
		server.start();
	}

	/**
	 * Starts a tenant that publishes {@link #signingKey()}.
	 *
	 * @return the running tenant; close it at the end of the test
	 */
	public static FakeAuth0 start() {
		return new FakeAuth0();
	}

	/**
	 * Generates a 2048-bit RSA key.
	 *
	 * @param kid the key id
	 * @return the key pair
	 */
	public static RSAKey newKey(String kid) {
		try {
			return new RSAKeyGenerator(2048).keyID(kid).generate();
		} catch (JOSEException e) {
			throw new IllegalStateException("RSA must be available on any JVM", e);
		}
	}

	/** @return the issuer, with the trailing slash Auth0 puts in {@code iss} */
	public String issuer() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
	}

	/** @return the key the tenant signs with (kid {@code k1}), published from the start */
	public RSAKey signingKey() {
		return signingKey;
	}

	/** @return how many JWKS requests reached the tenant */
	public int jwksRequests() {
		return jwksRequests.get();
	}

	/** @return how many requests of any kind (JWKS, discovery, any other path) reached the tenant */
	public int requests() {
		return requests.get();
	}

	/**
	 * Replaces the published keys (public parts only).
	 *
	 * @param keys the new JWKS content
	 */
	public void publish(RSAKey... keys) {
		publishJson(new JWKSet(Arrays.stream(keys).map(key -> (JWK) key.toPublicJWK()).toList()).toString());
	}

	/**
	 * Serves {@code json} as the JWKS, byte for byte (e.g. a JWKS padded to a given size).
	 *
	 * @param json the response body of {@code /.well-known/jwks.json}
	 */
	public void publishJson(String json) {
		publishedJson = json;
	}

	/**
	 * From now on the JWKS is sent one byte every {@code interval}, after the headers: each read of
	 * the client gets a byte well inside any per-read timeout, so only a limit on the whole fetch
	 * stops it (review r2, S-a).
	 *
	 * @param interval pause before each byte of the body
	 */
	public void drip(Duration interval) {
		drip = interval;
	}

	/** From now on JWKS requests are accepted and never answered, until {@link #resume()}. */
	public void hang() {
		hang = new CountDownLatch(1);
	}

	/** Answers the hung requests and every request after this one normally. */
	public void resume() {
		CountDownLatch current = hang;
		hang = null;
		if (current != null) {
			current.countDown();
		}
	}

	/**
	 * A token as Auth0 issues it for this API: RS256 with {@code kid} {@code k1}, {@code iss} {@link
	 * #issuer()}, {@code aud} {@link #AUDIENCE}, valid for 5 minutes, with the given roles in {@link
	 * TestJwtSupport#ROLES_CLAIM}.
	 *
	 * @param roles the roles claim
	 * @return the compact JWT
	 */
	public String token(List<String> roles) {
		return token(signingKey, claims -> claims.claim(TestJwtSupport.ROLES_CLAIM, roles));
	}

	/**
	 * A token signed with {@code key} (its {@code kid} goes in the header), starting from the valid
	 * claims of {@link #token(List)} without roles and then changed by {@code customizer}.
	 *
	 * @param key the signing key
	 * @param customizer changes the claims, e.g. another audience
	 * @return the compact JWT
	 */
	public String token(RSAKey key, Consumer<JWTClaimsSet.Builder> customizer) {
		Instant now = Instant.now();
		JWTClaimsSet.Builder claims =
				new JWTClaimsSet.Builder()
						.issuer(issuer())
						.audience(List.of(AUDIENCE))
						.subject("auth0|tester")
						.issueTime(Date.from(now))
						.expirationTime(Date.from(now.plus(Duration.ofMinutes(5))));
		customizer.accept(claims);
		try {
			SignedJWT jwt =
					new SignedJWT(
							new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).type(JOSEObjectType.JWT).build(),
							claims.build());
			jwt.sign(new RSASSASigner(key));
			return jwt.serialize();
		} catch (JOSEException e) {
			throw new IllegalStateException("Failed to sign a test JWT", e);
		}
	}

	private void serveJwks(HttpExchange exchange) throws IOException {
		jwksRequests.incrementAndGet();
		serveJson(exchange, publishedJson);
	}

	private void serveDiscovery(HttpExchange exchange) throws IOException {
		serveJson(exchange, "{\"issuer\":\"" + issuer() + "\",\"jwks_uri\":\"" + issuer() + ".well-known/jwks.json\"}");
	}

	private void serveNotFound(HttpExchange exchange) throws IOException {
		requests.incrementAndGet();
		exchange.sendResponseHeaders(404, -1);
		exchange.close();
	}

	private void serveJson(HttpExchange exchange, String json) throws IOException {
		requests.incrementAndGet();
		CountDownLatch current = hang;
		Duration interval = drip;
		try {
			if (current != null) {
				current.await(1, TimeUnit.MINUTES);
			}
			byte[] body = json.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				if (interval == null) {
					out.write(body);
					return;
				}
				for (byte b : body) {
					Thread.sleep(interval);
					out.write(b);
					out.flush();
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			exchange.close();
		}
	}

	@Override
	public void close() {
		resume();
		server.stop(0);
		executor.shutdownNow();
	}
}
