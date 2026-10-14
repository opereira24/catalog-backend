package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import pt.diamondcars.catalogbackend.config.support.FakeAuth0;

/**
 * An {@code AUTH0_AUDIENCE} pasted with a space or a newline around it, or with a control
 * character, is refused with one {@code WARN} and no request to the tenant (TASK-008, A.3; S-1 of
 * the TASK-002 review r3). Before, every token was rejected too ({@code aud} is compared exactly),
 * but in silence and only after the keys were fetched: the back-office answered 401 to everyone and
 * nothing in the log said why.
 *
 * <p>Mutant this class kills: removing the {@code isMalformedAudience} branch from {@code
 * SecurityConfig#jwtDecoder} (no warning, and the decoder fetches the tenant's keys).
 */
@ExtendWith(OutputCaptureExtension.class)
class MalformedAuth0AudienceTest {

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
	@ValueSource(
			strings = {
				" https://oteustand.pt/api",
				"https://oteustand.pt/api ",
				"https://oteustand.pt/api\n",
				"https://oteustand.pt/api\t",
				"https://oteu\u0000stand.pt/api"
			})
	void malformedAudienceRejectsEveryTokenWarnsOnceAndNeverCallsTheTenant(String audience, CapturedOutput output) {
		JwtDecoder decoder = new SecurityConfig().jwtDecoder(auth0.issuer(), audience);
		String token = auth0.token(List.of("admin"));

		Throwable rejection = catchThrowable(() -> decoder.decode(token));
		List<String> warnings = output.getAll().lines().filter(line -> line.contains("AUTH0_AUDIENCE invalido")).toList();

		// Soft: a decoder that lets the value through also rejects the token (another aud), so each
		// of the three facts is reported on its own.
		assertSoftly(softly -> {
			softly.assertThat(warnings).as("AUTH0_AUDIENCE warnings").hasSize(1);
			softly.assertThat(warnings).allSatisfy(line -> assertThat(line)
					.contains("WARN")
					.contains("AUTH0_AUDIENCE invalido, " + Auth0Issuer.forLog(audience) + ": "));
			softly.assertThat(auth0.requests()).as("requests of any kind to the tenant").isZero();
			softly.assertThat(rejection)
					.as("the rejection")
					.isExactlyInstanceOf(BadJwtException.class)
					.hasMessage(SecurityConfig.INVALID_TOKEN_MESSAGE);
			softly.assertThat(output.getAll()).doesNotContain("AUTH0_ISSUER_URI invalido").doesNotContain("\tat ");
		});
	}

	/** Control: the same tenant and token with the audience as it should be is accepted, without the warning. */
	@Test
	void wellFormedAudienceAcceptsTheTokenWithoutWarning(CapturedOutput output) {
		JwtDecoder decoder = new SecurityConfig().jwtDecoder(auth0.issuer(), FakeAuth0.AUDIENCE);

		assertThat(decoder.decode(auth0.token(List.of("admin"))).getAudience()).containsExactly(FakeAuth0.AUDIENCE);
		assertThat(output.getAll()).doesNotContain("AUTH0_AUDIENCE invalido");
	}
}
