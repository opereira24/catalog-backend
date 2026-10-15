package pt.diamondcars.catalogbackend.config.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Test-only JWTs (ported from {@code dcbo-backend}, TASK-002 AC E.1): a {@link JwtDecoder} backed by
 * a locally generated HMAC key and the matching signer, so a test can drive the real filter chain
 * with tokens that are actually decoded and validated, without ever contacting Auth0.
 */
public final class TestJwtSupport {

	/** Audience the test decoder accepts. */
	public static final String VALID_AUDIENCE = "https://catalog-backend-tests/api";

	/** The production roles claim (default of {@code app.auth0.roles-claim}). */
	public static final String ROLES_CLAIM = "https://oteustand.pt/roles";

	private static final SecretKey SECRET_KEY = generateHmacKey();
	private static final SecretKey FOREIGN_KEY = generateHmacKey();
	private static final Duration LIFETIME = Duration.ofMinutes(5);

	private TestJwtSupport() {}

	/**
	 * Builds a {@link JwtDecoder} that validates the standard timestamp claims plus a single
	 * audience, entirely offline (HMAC, no network).
	 *
	 * @param expectedAudience the only audience value this decoder accepts
	 * @return the configured decoder
	 */
	public static JwtDecoder decoderAcceptingAudience(String expectedAudience) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(SECRET_KEY).macAlgorithm(MacAlgorithm.HS256).build();
		OAuth2TokenValidator<Jwt> audienceValidator =
				new JwtClaimValidator<List<String>>(
						JwtClaimNames.AUD, audiences -> audiences != null && audiences.contains(expectedAudience));
		decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(audienceValidator));
		return decoder;
	}

	/**
	 * Signs a token with {@link #VALID_AUDIENCE}, valid for 5 minutes, carrying the given roles in
	 * {@link #ROLES_CLAIM}.
	 *
	 * @param subject the {@code sub} claim
	 * @param roles the roles claim value
	 * @return the signed, compact JWT
	 */
	public static String tokenWithRoles(String subject, List<String> roles) {
		return sign(claims(subject, List.of(VALID_AUDIENCE)).claim(ROLES_CLAIM, roles).build(), SECRET_KEY);
	}

	/**
	 * Signs a token with the given audience and, if {@code claimName} is not null, one extra claim.
	 *
	 * @param subject the {@code sub} claim
	 * @param audience the {@code aud} claim
	 * @param claimName name of an extra claim, or {@code null} for none
	 * @param claimValue value of the extra claim
	 * @return the signed, compact JWT
	 */
	public static String signedTokenWithClaim(String subject, List<String> audience, String claimName, Object claimValue) {
		JWTClaimsSet.Builder builder = claims(subject, audience);
		if (claimName != null) {
			builder.claim(claimName, claimValue);
		}
		return sign(builder.build(), SECRET_KEY);
	}

	/**
	 * Signs an otherwise valid token whose expiry is 10 minutes in the past (well beyond the
	 * default 60 s clock skew).
	 *
	 * @param subject the {@code sub} claim
	 * @return the signed, compact JWT
	 */
	public static String expiredToken(String subject) {
		Instant past = Instant.now().minus(Duration.ofMinutes(15));
		JWTClaimsSet claims =
				new JWTClaimsSet.Builder()
						.subject(subject)
						.audience(VALID_AUDIENCE)
						.issueTime(Date.from(past))
						.expirationTime(Date.from(past.plus(LIFETIME)))
						.claim(ROLES_CLAIM, List.of("admin"))
						.build();
		return sign(claims, SECRET_KEY);
	}

	/**
	 * Signs an otherwise valid token with a key the test decoder does not know.
	 *
	 * @param subject the {@code sub} claim
	 * @return the signed, compact JWT
	 */
	public static String tokenSignedWithAnotherKey(String subject) {
		return sign(claims(subject, List.of(VALID_AUDIENCE)).claim(ROLES_CLAIM, List.of("admin")).build(), FOREIGN_KEY);
	}

	private static JWTClaimsSet.Builder claims(String subject, List<String> audience) {
		Instant now = Instant.now();
		return new JWTClaimsSet.Builder()
				.subject(subject)
				.audience(audience)
				.issueTime(Date.from(now))
				.expirationTime(Date.from(now.plus(LIFETIME)));
	}

	private static String sign(JWTClaimsSet claims, SecretKey key) {
		try {
			SignedJWT signedJwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
			signedJwt.sign(new MACSigner(key.getEncoded()));
			return signedJwt.serialize();
		} catch (JOSEException e) {
			throw new IllegalStateException("Failed to sign a test JWT", e);
		}
	}

	private static SecretKey generateHmacKey() {
		try {
			KeyGenerator keyGenerator = KeyGenerator.getInstance("HmacSHA256");
			keyGenerator.init(256);
			return keyGenerator.generateKey();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("HmacSHA256 must be available on any JVM", e);
		}
	}
}
