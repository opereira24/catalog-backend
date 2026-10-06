package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pt.diamondcars.catalogbackend.config.LeadForwardingClientConfig;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The public endpoints over a real Tomcat on a random port, with a real HTTP client and no token
 * (TASK-002, AC E.5): status and body must be the ones the {@code dc} got before security existed
 * (measured on {@code develop} before this task, {@code timestamp} and ids aside).
 *
 * <p>A real port is required, not MockMvc: errors produced through {@code sendError} (the 406
 * below) are rendered by an {@code ERROR} dispatch to {@code /error}, which MockMvc never performs.
 * That dispatch is exactly what {@code SecurityConfig} has to permit; without it the 406 becomes a
 * 401 and only this class notices.
 *
 * <p>Each request carries its own {@code X-Forwarded-For}, so the per-IP rate limit of {@code POST
 * /api/leads} only triggers in the test that wants it. The lead forward to {@code dcbo-backend} is
 * answered by a {@link MockRestServiceServer}: no request leaves the machine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicEndpointsHttpTest extends AbstractPostgresIntegrationTest {

	private static final AtomicInteger NEXT_IP = new AtomicInteger(1);
	private static final String VALID_LEAD = """
			{"nome":"Joao Cliente","telefone":"913456789","email":"joao@example.com"}""";

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@LocalServerPort private int port;
	@Autowired private JsonMapper jsonMapper;

	@Autowired
	@Qualifier(LeadForwardingClientConfig.BEAN_NAME)
	private RestClient.Builder leadForwardingRestClientBuilder;

	@BeforeEach
	void answerLeadForwardsLocally() {
		MockRestServiceServer.bindTo(leadForwardingRestClientBuilder)
				.ignoreExpectOrder(true)
				.build()
				.expect(ExpectedCount.manyTimes(), anything())
				.andRespond(withSuccess());
	}

	@Test
	void carListIsPublic() throws Exception {
		HttpResponse<String> response = send(get("/api/cars"));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json(response).has("content")).isTrue();
	}

	@Test
	void invalidFilterKeepsItsBadRequest() throws Exception {
		assertApiError(send(get("/api/cars?precoMin=abc")), 400, "Parametro 'precoMin' com valor invalido", "/api/cars");
	}

	@Test
	void unknownCarKeepsItsNotFound() throws Exception {
		UUID id = UUID.randomUUID();

		assertApiError(send(get("/api/cars/" + id)), 404, "Carro nao encontrado: " + id, "/api/cars/" + id);
	}

	@Test
	void malformedCarIdKeepsItsBadRequest() throws Exception {
		assertApiError(send(get("/api/cars/not-a-uuid")), 400, "Parametro 'id' com valor invalido", "/api/cars/not-a-uuid");
	}

	@Test
	void highlightsArePublic() throws Exception {
		HttpResponse<String> response = send(get("/api/cars/highlights"));

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(json(response).isArray()).isTrue();
	}

	@Test
	void headOnTheCarListIsPublic() throws Exception {
		HttpResponse<String> response = send(request("/api/cars").method("HEAD", BodyPublishers.noBody()));

		assertThat(response.statusCode()).isEqualTo(200);
	}

	@Test
	void validLeadIsCreated() throws Exception {
		HttpResponse<String> response = send(postLead(VALID_LEAD));

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(json(response).get("id").asString()).isNotBlank();
	}

	@Test
	void leadForAnUnknownCarIsStillCreated() throws Exception {
		String body = """
				{"nome":"Joao Cliente","telefone":"913456789","carroId":"%s","carroMarca":"BMW","carroModelo":"320d"}"""
				.formatted(UUID.randomUUID());

		assertThat(send(postLead(body)).statusCode()).isEqualTo(201);
	}

	/** One invalid field only: with two, the order of the messages varies between runs (it did
	 * before this task too). */
	@Test
	void invalidLeadFieldKeepsItsBadRequest() throws Exception {
		assertApiError(
				send(postLead("{\"nome\":\"Joao Cliente\",\"telefone\":\"123\"}")),
				400,
				"telefone: formato de telefone invalido (ex: 912345678 ou +351912345678)",
				"/api/leads");
	}

	@Test
	void malformedLeadJsonKeepsItsBadRequest() throws Exception {
		assertApiError(send(postLead("{\"nome\":")), 400, "Corpo do pedido invalido ou malformado", "/api/leads");
	}

	@Test
	void plainTextLeadKeepsItsUnsupportedMediaType() throws Exception {
		HttpResponse<String> response =
				send(request("/api/leads").header("Content-Type", "text/plain").POST(BodyPublishers.ofString("ola")));

		assertApiError(response, 415, "Unsupported Media Type", "/api/leads");
	}

	/** The {@code ERROR} dispatch case: Spring MVC answers this one with {@code sendError}. */
	@Test
	void leadAskingForXmlKeepsItsNotAcceptable() throws Exception {
		HttpResponse<String> response =
				send(request("/api/leads")
						.header("Content-Type", "application/json")
						.header("Accept", "application/xml")
						.POST(BodyPublishers.ofString(VALID_LEAD)));

		assertThat(response.statusCode()).isEqualTo(406);
		assertThat(response.body()).isEmpty();
	}

	@Test
	void leadsAboveTheLimitKeepTheirTooManyRequests() throws Exception {
		String ip = nextIp();
		for (int i = 0; i < 5; i++) {
			assertThat(send(postLead(VALID_LEAD, ip)).statusCode()).isEqualTo(201);
		}

		assertApiError(send(postLead(VALID_LEAD, ip)), 429, "Demasiados pedidos - tente novamente mais tarde", "/api/leads");
	}

	@Test
	void actuatorHealthAndInfoArePublic() throws Exception {
		assertThat(json(send(get("/actuator/health"))).get("status").asString()).isEqualTo("UP");
		assertThat(send(get("/actuator/health/liveness")).statusCode()).isEqualTo(200);
		assertThat(send(get("/actuator/info")).statusCode()).isEqualTo(200);
	}

	/** The token is ignored on the public endpoints: a junk or expired token changes nothing. */
	@Test
	void junkTokenIsIgnoredOnThePublicEndpoints() throws Exception {
		assertThat(send(get("/api/cars").header("Authorization", "Bearer x.y.z")).statusCode()).isEqualTo(200);
		assertThat(send(postLead(VALID_LEAD).header("Authorization", "Bearer x.y.z")).statusCode()).isEqualTo(201);
	}

	@Test
	void routesOutsideThePublicListNeedAToken() throws Exception {
		assertApiError(send(get("/api/qualquer-coisa")), 401, "Autenticacao necessaria", "/api/qualquer-coisa");
		assertApiError(
				send(request("/api/cars").method("DELETE", BodyPublishers.noBody())), 401, "Autenticacao necessaria", "/api/cars");
	}

	/** {@code GET /error} asked for directly is a REQUEST dispatch, not an ERROR one: protected. */
	@Test
	void errorPageAskedForDirectlyNeedsAToken() throws Exception {
		assertThat(send(get("/error")).statusCode()).isEqualTo(401);
	}

	private HttpRequest.Builder get(String path) {
		return request(path).GET();
	}

	private HttpRequest.Builder postLead(String body) {
		return postLead(body, nextIp());
	}

	private HttpRequest.Builder postLead(String body, String ip) {
		return request(path("/api/leads"), ip).header("Content-Type", "application/json").POST(BodyPublishers.ofString(body));
	}

	private HttpRequest.Builder request(String path) {
		return request(path(path), nextIp());
	}

	private HttpRequest.Builder request(URI uri, String ip) {
		return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("X-Forwarded-For", ip);
	}

	private URI path(String path) {
		return URI.create("http://localhost:" + port + path);
	}

	private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
		return client.send(request.build(), BodyHandlers.ofString());
	}

	private JsonNode json(HttpResponse<String> response) {
		return jsonMapper.readTree(response.body());
	}

	private void assertApiError(HttpResponse<String> response, int status, String message, String path) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
		JsonNode body = json(response);
		assertThat(body.get("status").asInt()).isEqualTo(status);
		assertThat(body.get("message").asString()).isEqualTo(message);
		assertThat(body.get("path").asString()).isEqualTo(path);
		assertThat(body.get("timestamp").asString()).isNotBlank();
	}

	private static String nextIp() {
		int n = NEXT_IP.getAndIncrement();
		return "10.20." + (n / 250) + "." + (n % 250 + 1);
	}
}
