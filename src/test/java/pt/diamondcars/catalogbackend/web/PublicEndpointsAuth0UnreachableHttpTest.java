package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pt.diamondcars.catalogbackend.config.support.FakeAuth0;
import pt.diamondcars.catalogbackend.config.support.SecurityProbeConfig;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real {@code AUTH0_*} configured but Auth0 unreachable, over a real Tomcat (TASK-002, AC E.5,
 * sibling of {@link PublicEndpointsHttpTest}). The tenant issues a token and then goes down (its
 * port is closed). A protected request with that token ends in a 5xx with Spring Boot's error body,
 * deliberately never a 2xx and never a 401 (the token may be valid; a 401 would end the back-office
 * session), and without a stack trace in the log (review r1, S1). The public site, which never
 * calls the decoder, is not affected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SecurityProbeConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class PublicEndpointsAuth0UnreachableHttpTest extends AbstractPostgresIntegrationTest {

	/** Issued by the tenant before it went down: RS256, right issuer and audience, admin. */
	private static String token;

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@LocalServerPort private int port;
	@Autowired private JsonMapper jsonMapper;

	@DynamicPropertySource
	static void unreachableAuth0(DynamicPropertyRegistry registry) {
		String issuer;
		try (FakeAuth0 auth0 = FakeAuth0.start()) {
			issuer = auth0.issuer();
			token = auth0.token(List.of("admin"));
		}
		registry.add("app.auth0.issuer-uri", () -> issuer);
		registry.add("app.auth0.audience", () -> FakeAuth0.AUDIENCE);
	}

	@Test
	void protectedRouteWithTokenFailsWithServerErrorNeverSuccess(CapturedOutput output) throws Exception {
		HttpResponse<String> response = send(SecurityProbeConfig.PROBE_PATH, token);

		assertThat(response.statusCode()).isEqualTo(500);
		JsonNode body = jsonMapper.readTree(response.body());
		assertThat(body.get("status").asInt()).isEqualTo(500);
		assertThat(body.has("timestamp")).isTrue();
		assertThat(body.get("path").asString()).isEqualTo(SecurityProbeConfig.PROBE_PATH);
		assertThat(body.has("trace")).isFalse();
		assertThat(response.body()).doesNotContain("Exception");
		assertThat(output.getAll()).contains("JWKS do Auth0 indisponivel").doesNotContain("	at ").doesNotContain("Servlet.service()");
	}

	/** A token that is not even a JWT is invalid whatever Auth0's state: 401, no call to Auth0. */
	@Test
	void junkTokenIsStillUnauthorized() throws Exception {
		assertThat(send(SecurityProbeConfig.PROBE_PATH, "x.y.z").statusCode()).isEqualTo(401);
	}

	@Test
	void publicCatalogWithTheSameTokenIsUnaffected() throws Exception {
		assertThat(send("/api/cars", token).statusCode()).isEqualTo(200);
	}

	private HttpResponse<String> send(String path, String bearer) throws IOException, InterruptedException {
		HttpRequest request =
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
						.timeout(Duration.ofSeconds(30))
						.header("Authorization", "Bearer " + bearer)
						.GET()
						.build();
		return client.send(request, BodyHandlers.ofString());
	}
}
