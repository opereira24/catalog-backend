package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import pt.diamondcars.catalogbackend.config.support.FakeAuth0;
import pt.diamondcars.catalogbackend.config.support.SecurityProbeConfig;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * The whole application with {@code AUTH0_ISSUER_URI} set to the "Domain" the Auth0 dashboard shows
 * ({@code oteustand.eu.auth0.com}, no scheme, no slash), over a real Tomcat (TASK-002, review r2,
 * IMPORTANTE 1). Review r2 measured this value stopping the application from starting ({@code
 * BeanCreationException}), public site and health check included. Now the context starts, the
 * public site and the health check answer as always, protected routes answer 401 to any token, and
 * the reason is logged once.
 *
 * <p>Mutant this class kills: removing the {@code Auth0Issuer.problem(...)} check from {@code
 * SecurityConfig#jwtDecoder} (the context fails to start, so every test here fails).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SecurityProbeConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {"app.auth0.issuer-uri=oteustand.eu.auth0.com", "app.auth0.audience=" + FakeAuth0.AUDIENCE})
class MalformedAuth0IssuerContextTest extends AbstractPostgresIntegrationTest {

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@LocalServerPort private int port;

	@Test
	void publicSiteAndHealthCheckAnswer() throws Exception {
		assertThat(status("/api/cars", null)).isEqualTo(200);
		assertThat(status("/actuator/health", null)).isEqualTo(200);
	}

	@Test
	void protectedRoutesRejectEveryTokenWith401() throws Exception {
		String rs256Token;
		try (FakeAuth0 tenant = FakeAuth0.start()) {
			rs256Token = tenant.token(List.of("admin"));
		}

		assertThat(status(SecurityProbeConfig.ADMIN_PROBE_PATH, rs256Token)).isEqualTo(401);
		assertThat(status(SecurityProbeConfig.PROBE_PATH, "x.y.z")).isEqualTo(401);
		assertThat(status(SecurityProbeConfig.PROBE_PATH, null)).isEqualTo(401);
	}

	/**
	 * One warning, at startup (the output capture of this class starts before its context, which no
	 * other class shares), that names the variable, the problem and the expected form; requests add
	 * nothing to the log.
	 */
	@Test
	void oneWarningAtStartupAndNonePerRequest(CapturedOutput output) throws Exception {
		for (int i = 0; i < 20; i++) {
			assertThat(status(SecurityProbeConfig.PROBE_PATH, "x.y.z")).isEqualTo(401);
		}

		assertThat(output.getAll().lines().filter(line -> line.contains("AUTH0_ISSUER_URI")))
				.singleElement()
				.satisfies(
						line ->
								assertThat(line)
										.contains("WARN")
										.contains("AUTH0_ISSUER_URI invalido, \"oteustand.eu.auth0.com\": falta o esquema https://. Formato esperado")
										.contains("https://oteustand.eu.auth0.com/"));
		assertThat(output.getAll()).doesNotContain("\tat ");
	}

	private int status(String path, String token) throws Exception {
		HttpRequest.Builder request =
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10)).GET();
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return client.send(request.build(), BodyHandlers.discarding()).statusCode();
	}
}
