package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

/**
 * Real {@code AUTH0_*} configured and Auth0 hung (accepts the connection, never answers) before
 * the keys were ever fetched, over a real Tomcat (TASK-002, review r1, IMPORTANTE 1). Review r1
 * measured 250 such requests putting {@code GET /api/cars} into 30 s timeouts: the requests queued
 * for 3 s each behind the OIDC discovery. Now one request waits for Auth0, a few wait for it, the
 * rest get their 500 at once, and the public site keeps answering in well under a second.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SecurityProbeConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class PublicEndpointsAuth0HungHttpTest extends AbstractPostgresIntegrationTest {

	/** More than Tomcat's 200 worker threads, as in the measurement of review r1. */
	private static final int PROTECTED_REQUESTS = 250;

	private static final FakeAuth0 AUTH0 = FakeAuth0.start();

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	private final ExecutorService pool = Executors.newFixedThreadPool(PROTECTED_REQUESTS);

	@LocalServerPort private int port;

	@DynamicPropertySource
	static void hungAuth0(DynamicPropertyRegistry registry) {
		registry.add("app.auth0.issuer-uri", AUTH0::issuer);
		registry.add("app.auth0.audience", () -> FakeAuth0.AUDIENCE);
	}

	@AfterAll
	static void stopTenant() {
		AUTH0.close();
	}

	@Test
	void floodOfProtectedRequestsLeavesThePublicSiteFast(CapturedOutput output) {
		String token = AUTH0.token(List.of("admin"));
		AUTH0.hang();

		assertTimeoutPreemptively(
				Duration.ofSeconds(30),
				() -> {
					long start = System.nanoTime();
					List<Future<Integer>> flood = new ArrayList<>();
					for (int i = 0; i < PROTECTED_REQUESTS; i++) {
						// spread over ~0.5 s, well inside the 3 s fetch: 250 simultaneous connects
						// overflow the listen backlog (Tomcat's acceptCount, 100) and get refused
						flood.add(pool.submit(() -> status(SecurityProbeConfig.PROBE_PATH, token)));
						Thread.sleep(2);
					}
					while (AUTH0.jwksRequests() == 0) {
						Thread.sleep(10);
					}
					for (int i = 0; i < 5; i++) {
						long publicStart = System.nanoTime();
						assertThat(status("/api/cars", token)).isEqualTo(200);
						assertThat(Duration.ofNanos(System.nanoTime() - publicStart)).isLessThan(Duration.ofSeconds(1));
					}
					for (Future<Integer> request : flood) {
						assertThat(request.get()).isEqualTo(500);
					}
					assertThat(Duration.ofNanos(System.nanoTime() - start))
							.as("one 3 s fetch plus waiters capped at 6 s, never 250 x 3 s")
							.isLessThan(Duration.ofSeconds(12));
				});

		assertThat(AUTH0.jwksRequests()).isEqualTo(1);
		assertThat(output.getAll().lines().filter(line -> line.contains("JWKS do Auth0 indisponivel"))).hasSize(1);
		assertThat(output.getAll()).doesNotContain("\tat ");
		pool.shutdownNow();
	}

	private int status(String path, String token) throws Exception {
		HttpRequest request =
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
						.timeout(Duration.ofSeconds(25))
						.header("Authorization", "Bearer " + token)
						.GET()
						.build();
		return client.send(request, BodyHandlers.discarding()).statusCode();
	}
}
