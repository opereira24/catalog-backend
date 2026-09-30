package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import pt.diamondcars.catalogbackend.config.LeadForwardingClientConfig;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.lead.LeadOrigin;
import pt.diamondcars.catalogbackend.domain.lead.LeadRepository;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * End-to-end tests of {@link PublicLeadController}, {@code LeadService}, {@code LeadForwarder}
 * and {@link RateLimitInterceptor} through the real servlet filter chain (TASK-015), using {@link
 * MockMvc} against a real PostgreSQL container ({@link AbstractPostgresIntegrationTest},
 * TASK-002) and a {@link MockRestServiceServer} standing in for {@code dcbo-backend} — never a
 * real HTTP call.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.MOCK,
		properties = "app.leads.forward.retry.enabled=false")
@AutoConfigureMockMvc
class PublicLeadControllerTest extends AbstractPostgresIntegrationTest {

	@Autowired private MockMvc mockMvc;
	@Autowired private LeadRepository leadRepository;

	@Autowired
	@Qualifier(LeadForwardingClientConfig.BEAN_NAME)
	private RestClient.Builder leadForwardingRestClientBuilder;

	private MockRestServiceServer dcboBackend;

	/**
	 * Clears every lead written by a previous test and rebinds a fresh {@link
	 * MockRestServiceServer} to the forwarding client, so tests never influence each other on the
	 * shared, JVM-wide container/context ({@link AbstractPostgresIntegrationTest}).
	 */
	@BeforeEach
	void setUp() {
		leadRepository.deleteAll();
		dcboBackend = MockRestServiceServer.bindTo(leadForwardingRestClientBuilder).build();
	}

	/**
	 * Acceptance criterion: a valid payload with a {@code carroId} is persisted with {@code origem
	 * = "website"}, and the forward to {@code dcbo-backend} succeeds and marks {@code
	 * forwardedAt}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void createsALeadWithCarAndForwardsItSuccessfully() throws Exception {
		dcboBackend
				.expect(requestTo("http://localhost:8080/internal/leads"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("X-Internal-Token", "placeholder"))
				.andRespond(withSuccess());

		String body =
				"""
				{"nome":"Joao Cliente","telefone":"913456789","email":"joao@example.com",
				 "carroId":"%s","carroMarca":"BMW","carroModelo":"320d"}
				"""
						.formatted(UUID.randomUUID());

		String responseBody =
				mockMvc
						.perform(
								post("/api/leads")
										.with(request -> {
											request.setRemoteAddr("10.0.0.1");
											return request;
										})
										.contentType(MediaType.APPLICATION_JSON)
										.content(body))
						.andExpect(status().isCreated())
						.andExpect(jsonPath("$.id").exists())
						.andReturn()
						.getResponse()
						.getContentAsString();

		dcboBackend.verify();

		UUID leadId = extractId(responseBody);
		Lead saved = leadRepository.findById(leadId).orElseThrow();
		assertThat(saved.getOrigem()).isEqualTo(LeadOrigin.WEBSITE);
		assertThat(saved.getForwardedAt()).isNotNull();
	}

	/**
	 * Acceptance criterion: a valid payload without {@code carroId} (general contact) is persisted
	 * with {@code origem = "website-contacto"}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void createsAGeneralContactLeadWithoutACar() throws Exception {
		dcboBackend.expect(requestTo("http://localhost:8080/internal/leads")).andRespond(withSuccess());

		String body = """
				{"nome":"Maria Interessada","telefone":"914567890"}
				""";

		String responseBody =
				mockMvc
						.perform(
								post("/api/leads")
										.with(request -> {
											request.setRemoteAddr("10.0.0.2");
											return request;
										})
										.contentType(MediaType.APPLICATION_JSON)
										.content(body))
						.andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString();

		UUID leadId = extractId(responseBody);
		Lead saved = leadRepository.findById(leadId).orElseThrow();
		assertThat(saved.getOrigem()).isEqualTo(LeadOrigin.WEBSITE_CONTACTO);
	}

	/**
	 * Acceptance criterion: missing {@code telefone} is rejected with 400 naming the field, and a
	 * malformed {@code email} is also rejected with 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsMissingTelefoneAndMalformedEmail() throws Exception {
		mockMvc
				.perform(
						post("/api/leads")
								.with(request -> {
									request.setRemoteAddr("10.0.0.3");
									return request;
								})
								.contentType(MediaType.APPLICATION_JSON)
								.content("""
										{"nome":"Sem Telefone"}
										"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("telefone")));

		mockMvc
				.perform(
						post("/api/leads")
								.with(request -> {
									request.setRemoteAddr("10.0.0.3");
									return request;
								})
								.contentType(MediaType.APPLICATION_JSON)
								.content("""
										{"nome":"Email Invalido","telefone":"913456789","email":"nao-e-email"}
										"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("email")));

		assertThat(leadRepository.count()).isZero();
	}

	/**
	 * Acceptance criterion: a {@code mensagem} containing {@code <script>alert(1)</script>} is
	 * persisted without the literal {@code <script} substring.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void sanitizesAMensagemContainingAScriptTag() throws Exception {
		dcboBackend.expect(requestTo("http://localhost:8080/internal/leads")).andRespond(withSuccess());

		String responseBody =
				mockMvc
						.perform(
								post("/api/leads")
										.with(request -> {
											request.setRemoteAddr("10.0.0.4");
											return request;
										})
										.contentType(MediaType.APPLICATION_JSON)
										.content(
												"""
												{"nome":"Atacante","telefone":"913456789",
												 "mensagem":"<script>alert(1)</script>"}
												"""))
						.andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString();

		UUID leadId = extractId(responseBody);
		Lead saved = leadRepository.findById(leadId).orElseThrow();
		assertThat(saved.getMensagem()).doesNotContain("<script");
	}

	/**
	 * Acceptance criterion: 5 submissions from the same IP within the configured window succeed,
	 * and the 6th is rejected with 429.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsTheSixthSubmissionFromTheSameIpWithinTheWindow() throws Exception {
		dcboBackend
				.expect(
						org.springframework.test.web.client.ExpectedCount.times(5),
						requestTo("http://localhost:8080/internal/leads"))
				.andRespond(withSuccess());

		String body = """
				{"nome":"Rate Limitado","telefone":"913456789"}
				""";

		for (int i = 0; i < 5; i++) {
			mockMvc
					.perform(
							post("/api/leads")
									.with(request -> {
										request.setRemoteAddr("10.0.0.5");
										return request;
									})
									.contentType(MediaType.APPLICATION_JSON)
									.content(body))
					.andExpect(status().isCreated());
		}

		mockMvc
				.perform(
						post("/api/leads")
								.with(request -> {
									request.setRemoteAddr("10.0.0.5");
									return request;
								})
								.contentType(MediaType.APPLICATION_JSON)
								.content(body))
				.andExpect(status().isTooManyRequests());
	}

	/**
	 * Acceptance criterion: when the forwarding call to {@code dcbo-backend} fails, {@code
	 * POST /api/leads} still responds 201, the lead is persisted, and {@code forwardedAt} stays
	 * {@code null}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void respondsCreatedEvenWhenForwardingFails() throws Exception {
		dcboBackend.expect(requestTo("http://localhost:8080/internal/leads")).andRespond(withServerError());

		String responseBody =
				mockMvc
						.perform(
								post("/api/leads")
										.with(request -> {
											request.setRemoteAddr("10.0.0.6");
											return request;
										})
										.contentType(MediaType.APPLICATION_JSON)
										.content("""
												{"nome":"Falha Encaminho","telefone":"913456789"}
												"""))
						.andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString();

		UUID leadId = extractId(responseBody);
		Lead saved = leadRepository.findById(leadId).orElseThrow();
		assertThat(saved.getForwardedAt()).isNull();
		assertThat(saved.getForwardAttempts()).isEqualTo(1);
	}

	/**
	 * Extracts {@code id} from a {@code POST /api/leads} JSON response body.
	 *
	 * @param responseBody the raw JSON response body
	 * @return the parsed id
	 */
	private static UUID extractId(String responseBody) {
		return UUID.fromString(responseBody.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}
}
