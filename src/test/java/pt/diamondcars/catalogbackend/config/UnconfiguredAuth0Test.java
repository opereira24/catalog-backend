package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.test.web.servlet.MockMvc;
import pt.diamondcars.catalogbackend.config.support.SecurityProbeConfig;
import pt.diamondcars.catalogbackend.config.support.TestJwtSupport;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * The production {@link JwtDecoder} with the default properties, i.e. without {@code AUTH0_*}, the
 * state the service is deployed in until the variables are added to Render (TASK-002, AC E.4). No
 * test decoder: whatever token arrives, the answer is 401, never 2xx, and nothing is fetched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(SecurityProbeConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class UnconfiguredAuth0Test extends AbstractPostgresIntegrationTest {

	@Autowired private MockMvc mockMvc;
	@Autowired private JwtDecoder jwtDecoder;

	/** A token anyone can forge: right audience and an admin role, signed with a key of their own. */
	private static final String FORGED_ADMIN_TOKEN =
			TestJwtSupport.signedTokenWithClaim(
					"auth0|attacker", List.of("https://oteustand.pt/api"), TestJwtSupport.ROLES_CLAIM, List.of("admin"));

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"forged", "junk"})
	void anyTokenIsRejectedWith401(String kind) throws Exception {
		String token = kind.equals("forged") ? FORGED_ADMIN_TOKEN : "x.y.z";

		mockMvc.perform(get(SecurityProbeConfig.ADMIN_PROBE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, not(containsStringIgnoringCase("configur"))))
				.andExpect(jsonPath("$.message").value("Autenticacao necessaria"));
	}

	@Test
	void noTokenIsRejectedWith401() throws Exception {
		mockMvc.perform(get(SecurityProbeConfig.PROBE_PATH)).andExpect(status().isUnauthorized());
	}

	@Test
	void publicCatalogStillWorks() throws Exception {
		mockMvc.perform(get("/api/cars").header(HttpHeaders.AUTHORIZATION, "Bearer " + FORGED_ADMIN_TOKEN))
				.andExpect(status().isOk());
	}

	@Test
	void decoderBeanRejectsEveryTokenWithoutNetwork() {
		assertThatThrownBy(() -> jwtDecoder.decode(FORGED_ADMIN_TOKEN))
				.isInstanceOf(BadJwtException.class)
				.hasMessage(SecurityConfig.INVALID_TOKEN_MESSAGE);
	}

	@Test
	void missingConfigurationIsReportedOnceAsWarning(CapturedOutput output) {
		new SecurityConfig().jwtDecoder("", "");

		assertThat(output.getAll()).contains("WARN").contains("AUTH0_ISSUER_URI e/ou AUTH0_AUDIENCE em falta");
	}

	/**
	 * Accepting tokens without checking {@code aud} would let in tokens issued for other APIs of
	 * the tenant. The issuer is a closed local port on purpose: if this branch ever built a real
	 * decoder, the discovery would fail with {@link JwtDecoderInitializationException} instead of
	 * {@link BadJwtException}, and no test could reach a real tenant (an earlier version of this
	 * test used the production issuer and a mutant that dropped the audience check survived).
	 */
	@Test
	void issuerWithoutAudienceAlsoRejectsEveryTokenWithoutNetwork() throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}
		JwtDecoder decoder = new SecurityConfig().jwtDecoder("http://127.0.0.1:" + closedPort + "/", "");

		assertThatThrownBy(() -> decoder.decode(FORGED_ADMIN_TOKEN))
				.isExactlyInstanceOf(BadJwtException.class)
				.hasMessage(SecurityConfig.INVALID_TOKEN_MESSAGE);
	}

	/**
	 * A server that accepts the connection and never answers stands for a hung Auth0: the OIDC
	 * discovery gives up after the 3 s read timeout instead of holding the request thread forever.
	 */
	@Test
	void hungAuth0FailsFastWithInitializationError() throws Exception {
		try (ServerSocket silentServer = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
			String issuer = "http://127.0.0.1:" + silentServer.getLocalPort() + "/";
			JwtDecoder decoder = new SecurityConfig().jwtDecoder(issuer, "https://oteustand.pt/api");

			long start = System.nanoTime();
			assertThatThrownBy(() -> decoder.decode(FORGED_ADMIN_TOKEN)).isInstanceOf(JwtDecoderInitializationException.class);
			Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

			assertThat(elapsed).isGreaterThanOrEqualTo(SecurityConfig.AUTH0_TIMEOUT.minusMillis(500)).isLessThan(Duration.ofSeconds(10));
		}
	}
}
