package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import pt.diamondcars.catalogbackend.config.support.FakeAuth0;

/**
 * A malformed {@code AUTH0_ISSUER_URI} never stops the application from starting (TASK-002, review
 * r2, IMPORTANTE 1): building the {@code jwtDecoder} bean used to throw for the bare domain the
 * Auth0 dashboard shows, so the context failed and the public site went down with it. Now the bean
 * is the decoder that rejects every token (401), nothing is fetched, and one {@code WARN} says what
 * is wrong. {@link MalformedAuth0IssuerContextTest} proves the same with a whole application.
 *
 * <p>Mutant this class kills: removing the {@code Auth0Issuer.problem(...)} check from {@code
 * SecurityConfig#jwtDecoder} (the bare domain throws again, and the fetchable variants below reach
 * the tenant).
 */
@ExtendWith(OutputCaptureExtension.class)
class MalformedAuth0IssuerTest {

	private FakeAuth0 auth0;

	@BeforeEach
	void startTenant() {
		auth0 = FakeAuth0.start();
	}

	@AfterEach
	void stopTenant() {
		auth0.close();
	}

	@ParameterizedTest(name = "\"{0}\"")
	@MethodSource("pt.diamondcars.catalogbackend.config.Auth0IssuerTest#malformedIssuers")
	void malformedIssuerRejectsEveryTokenAndWarnsOnce(String issuer, String hint, CapturedOutput output) {
		JwtDecoder decoder = new SecurityConfig().jwtDecoder(issuer, FakeAuth0.AUDIENCE);

		assertThatThrownBy(() -> decoder.decode(auth0.token(List.of("admin"))))
				.isExactlyInstanceOf(BadJwtException.class)
				.hasMessage(SecurityConfig.INVALID_TOKEN_MESSAGE);
		assertThat(output.getAll().lines().filter(line -> line.contains("AUTH0_ISSUER_URI invalido")))
				.singleElement()
				.satisfies(
						line ->
								assertThat(line)
										.contains("WARN")
										.contains("AUTH0_ISSUER_URI invalido, " + Auth0Issuer.forLog(issuer) + ": ")
										.contains(hint)
										.contains(Auth0Issuer.EXAMPLE));
		assertThat(output.getAll()).doesNotContain("\tat ");
	}

	/**
	 * Malformed spellings of a tenant that is up. Without the check the key source is built from
	 * them: it either fails to build (the context does not start) or fetches keys from the tenant
	 * (the URL ignores the case of the scheme, or keeps a path, query or fragment) and the tokens end
	 * up rejected later, for another reason and only per request. With the check, the tenant never
	 * sees a request. {@code {base}} is the tenant's issuer without the trailing slash, {@code
	 * {BASE}} the same with the scheme in uppercase.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("fetchableButMalformed")
	void malformedIssuerOfAReachableTenantNeverFetchesKeys(String variant) {
		String base = auth0.issuer().substring(0, auth0.issuer().length() - 1);
		String issuer = variant.replace("{base}", base).replace("{BASE}", base.replace("http", "HTTP"));
		JwtDecoder decoder = new SecurityConfig().jwtDecoder(issuer, FakeAuth0.AUDIENCE);

		for (int i = 0; i < 3; i++) {
			assertThatThrownBy(() -> decoder.decode(auth0.token(List.of("admin")))).isExactlyInstanceOf(BadJwtException.class);
		}
		assertThat(auth0.requests()).as("requests of any kind to the tenant").isZero();
	}

	/** Blank is "missing", not "malformed": the same decoder, with the missing-configuration warning. */
	@ParameterizedTest(name = "\"{0}\"")
	@ValueSource(strings = {"", " ", "\t", "\n", "  \r\n "})
	void blankIssuerIsTreatedAsMissing(String issuer, CapturedOutput output) {
		JwtDecoder decoder = new SecurityConfig().jwtDecoder(issuer, FakeAuth0.AUDIENCE);

		assertThatThrownBy(() -> decoder.decode(auth0.token(List.of("admin"))))
				.isExactlyInstanceOf(BadJwtException.class)
				.hasMessage(SecurityConfig.INVALID_TOKEN_MESSAGE);
		assertThat(output.getAll()).contains("AUTH0_ISSUER_URI e/ou AUTH0_AUDIENCE em falta").doesNotContain("\tat ");
		assertThat(auth0.requests()).isZero();
	}

	static Stream<Arguments> fetchableButMalformed() {
		return Stream.of(
				Arguments.of("{base}"),
				Arguments.of("{BASE}/"),
				Arguments.of("{base}/tenant/"),
				Arguments.of("{base}/?x=1"),
				Arguments.of("{base}/#x"),
				Arguments.of("{base}/ "));
	}
}
