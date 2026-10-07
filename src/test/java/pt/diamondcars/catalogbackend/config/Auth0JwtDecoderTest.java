package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import pt.diamondcars.catalogbackend.config.support.FakeAuth0;
import pt.diamondcars.catalogbackend.config.support.TestJwtSupport;

/**
 * The production {@link JwtDecoder} ({@code SecurityConfig#jwtDecoder} with {@code AUTH0_*} set)
 * decoding real RS256 tokens from an Auth0 tenant in the test JVM (TASK-002, review r1, IMPORTANTE
 * 2). Every other token test uses the HMAC test decoder, which has its own audience check; this is
 * the class that proves the production decoder rejects each kind of bad token. Every rejection must
 * be a {@link BadJwtException} (401), never a 2xx and never a server error.
 *
 * <p>Mutants this class kills (measured): deleting {@code decoder.setJwtValidator(...)} (the
 * decoder then only checks the time), and validating the issuer without the audience.
 */
class Auth0JwtDecoderTest {

	private FakeAuth0 auth0;
	private JwtDecoder decoder;

	@BeforeEach
	void startTenant() {
		auth0 = FakeAuth0.start();
		decoder = new SecurityConfig().jwtDecoder(auth0.issuer(), FakeAuth0.AUDIENCE);
	}

	@AfterEach
	void stopTenant() {
		auth0.close();
	}

	@Test
	void validAdminTokenDecodesWithTheAdminRole() {
		Jwt jwt = decoder.decode(auth0.token(List.of("admin")));

		assertThat(jwt.getSubject()).isEqualTo("auth0|tester");
		assertThat(jwt.getAudience()).containsExactly(FakeAuth0.AUDIENCE);
		assertThat(new Auth0RolesConverter(TestJwtSupport.ROLES_CLAIM).convert(jwt))
				.extracting(GrantedAuthority::getAuthority)
				.containsExactly("ROLE_ADMIN");
	}

	@Test
	void audienceAsASingleStringOrAmongOthersIsAccepted() {
		assertThat(decoder.decode(token(c -> c.audience(FakeAuth0.AUDIENCE))).getAudience())
				.containsExactly(FakeAuth0.AUDIENCE);
		assertThat(decoder.decode(token(c -> c.audience(List.of("https://oteustand.eu.auth0.com/userinfo", FakeAuth0.AUDIENCE))))
						.getAudience())
				.hasSize(2);
	}

	@Test
	void keysAreFetchedOnceForManyValidTokens() {
		for (int i = 0; i < 20; i++) {
			decoder.decode(auth0.token(List.of("user")));
		}

		assertThat(auth0.jwksRequests()).isEqualTo(1);
	}

	static Stream<Arguments> invalidTokens() {
		RSAKey sameKidOtherKey = FakeAuth0.newKey("k1");
		RSAKey unknownKey = FakeAuth0.newKey("k-unknown");
		Instant past = Instant.now().minus(Duration.ofMinutes(15));
		return Stream.of(
				Arguments.of("another audience", (TokenFactory) a -> a.token(a.signingKey(), c -> c.audience("https://another-api"))),
				Arguments.of("no audience", (TokenFactory) a -> a.token(a.signingKey(), c -> c.audience((String) null))),
				Arguments.of("another issuer", (TokenFactory) a -> a.token(a.signingKey(), c -> c.issuer("https://evil.eu.auth0.com/"))),
				Arguments.of("no issuer", (TokenFactory) a -> a.token(a.signingKey(), c -> c.issuer(null))),
				Arguments.of("expired", (TokenFactory) a -> a.token(a.signingKey(), c -> c.issueTime(Date.from(past)).expirationTime(Date.from(past.plus(Duration.ofMinutes(5)))))),
				Arguments.of("not before in the future", (TokenFactory) a -> a.token(a.signingKey(), c -> c.notBeforeTime(Date.from(Instant.now().plus(Duration.ofMinutes(10)))))),
				Arguments.of("another key with the same kid", (TokenFactory) a -> a.token(sameKidOtherKey, c -> {})),
				Arguments.of("unknown kid", (TokenFactory) a -> a.token(unknownKey, c -> {})),
				Arguments.of("alg none", (TokenFactory) a -> new PlainJWT(validClaims(a)).serialize()),
				Arguments.of("HS256 with the public key as secret", (TokenFactory) Auth0JwtDecoderTest::hmacWithThePublicKey),
				Arguments.of("payload changed after signing", (TokenFactory) Auth0JwtDecoderTest::tamperedPayload),
				Arguments.of("junk", (TokenFactory) a -> "x.y.z"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidTokens")
	void invalidTokenIsRejectedAsInvalid(String kind, TokenFactory tokenFactory) throws Exception {
		String token = tokenFactory.create(auth0);

		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void junkTokenNeverReachesAuth0() {
		assertThatThrownBy(() -> decoder.decode("x.y.z")).isInstanceOf(BadJwtException.class);

		assertThat(auth0.jwksRequests()).isZero();
	}

	@Test
	void jwksUrlFollowsAuth0WithOrWithoutTrailingSlash() {
		assertThat(Auth0JwkSource.jwkSetUrl("https://oteustand.eu.auth0.com/"))
				.hasToString("https://oteustand.eu.auth0.com/.well-known/jwks.json");
		assertThat(Auth0JwkSource.jwkSetUrl("https://oteustand.eu.auth0.com"))
				.hasToString("https://oteustand.eu.auth0.com/.well-known/jwks.json");
	}

	@FunctionalInterface
	interface TokenFactory {
		String create(FakeAuth0 auth0) throws Exception;
	}

	private String token(Consumer<JWTClaimsSet.Builder> customizer) {
		return auth0.token(auth0.signingKey(), customizer.andThen(c -> c.claim(TestJwtSupport.ROLES_CLAIM, List.of("admin"))));
	}

	private static JWTClaimsSet validClaims(FakeAuth0 auth0) throws Exception {
		return SignedJWT.parse(auth0.token(List.of("admin"))).getJWTClaimsSet();
	}

	/** The classic algorithm-confusion attack: HMAC keyed with the published RSA public key. */
	private static String hmacWithThePublicKey(FakeAuth0 auth0) throws Exception {
		SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("k1").build(), validClaims(auth0));
		jwt.sign(new MACSigner(auth0.signingKey().toRSAPublicKey().getEncoded()));
		return jwt.serialize();
	}

	/** A user token whose payload is swapped for an admin one, keeping the original signature. */
	private static String tamperedPayload(FakeAuth0 auth0) {
		String[] user = auth0.token(List.of("user")).split("\\.");
		String[] admin = auth0.token(List.of("admin")).split("\\.");
		assertThat(Base64.getUrlDecoder().decode(admin[1])).isNotEqualTo(Base64.getUrlDecoder().decode(user[1]));
		return user[0] + "." + admin[1] + "." + user[2];
	}
}
