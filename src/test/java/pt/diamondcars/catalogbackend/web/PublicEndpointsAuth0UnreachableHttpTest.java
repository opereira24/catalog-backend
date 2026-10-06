package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pt.diamondcars.catalogbackend.config.support.SecurityProbeConfig;
import pt.diamondcars.catalogbackend.config.support.TestJwtSupport;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Real {@code AUTH0_*} configured but Auth0 unreachable (issuer on a closed local port), over a
 * real Tomcat (TASK-002, AC E.5, sibling of {@link PublicEndpointsHttpTest}). A protected request
 * with a token ends in a 5xx with Spring Boot's error body, deliberately never a 2xx and never a
 * 401 (the token may be valid; a 401 would end the back-office session). The public site, which
 * never calls the decoder, is not affected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SecurityProbeConfig.class)
class PublicEndpointsAuth0UnreachableHttpTest extends AbstractPostgresIntegrationTest {

	private static final String TOKEN =
			TestJwtSupport.signedTokenWithClaim(
					"auth0|someone", List.of("https://oteustand.pt/api"), TestJwtSupport.ROLES_CLAIM, List.of("admin"));

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@LocalServerPort private int port;
	@Autowired private JsonMapper jsonMapper;

	@DynamicPropertySource
	static void unreachableAuth0(DynamicPropertyRegistry registry) throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}
		registry.add("app.auth0.issuer-uri", () -> "http://127.0.0.1:" + closedPort + "/");
		registry.add("app.auth0.audience", () -> "https://oteustand.pt/api");
	}

	@Test
	void protectedRouteWithTokenFailsWithServerErrorNeverSuccess() throws Exception {
		HttpResponse<String> response = send(SecurityProbeConfig.PROBE_PATH);

		assertThat(response.statusCode()).isEqualTo(500);
		JsonNode body = jsonMapper.readTree(response.body());
		assertThat(body.get("status").asInt()).isEqualTo(500);
		assertThat(body.has("timestamp")).isTrue();
		assertThat(body.get("path").asString()).isEqualTo(SecurityProbeConfig.PROBE_PATH);
		assertThat(body.has("trace")).isFalse();
		assertThat(response.body()).doesNotContain("Exception");
	}

	@Test
	void publicCatalogWithTheSameTokenIsUnaffected() throws Exception {
		assertThat(send("/api/cars").statusCode()).isEqualTo(200);
	}

	private HttpResponse<String> send(String path) throws IOException, InterruptedException {
		HttpRequest request =
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
						.timeout(Duration.ofSeconds(30))
						.header("Authorization", "Bearer " + TOKEN)
						.GET()
						.build();
		return client.send(request, BodyHandlers.ofString());
	}
}
