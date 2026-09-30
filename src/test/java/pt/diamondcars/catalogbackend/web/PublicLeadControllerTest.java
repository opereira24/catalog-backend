package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
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
 * real HTTP call. {@code app.leads.forward.retry.enabled=false} (requirement 7) is inherited from
 * {@link AbstractPostgresIntegrationTest}, not repeated here (review r1, IMPORTANTE 4).
 *
 * <p>Every test that triggers a forward calls {@link #awaitForwardOutcome(UUID)} before finishing
 * (review r1, IMPORTANTE 5: forwarding is now {@code @Async}), so this test class never races the
 * background executor thread — without that, a still-in-flight forward from one test could reach
 * {@link #dcboBackend} only after the next test's {@link #setUp()} rebinds it to a different set
 * of expectations, corrupting an unrelated test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
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

		UUID leadId = extractId(responseBody);
		awaitForwardOutcome(leadId);
		dcboBackend.verify();

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
		awaitForwardOutcome(leadId);
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
				.andExpect(jsonPath("$.message").value(Matchers.containsString("telefone")));

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
				.andExpect(jsonPath("$.message").value(Matchers.containsString("email")));

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
		awaitForwardOutcome(leadId);
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
		dcboBackend.expect(ExpectedCount.times(5), requestTo("http://localhost:8080/internal/leads")).andRespond(withSuccess());

		String body = """
				{"nome":"Rate Limitado","telefone":"913456789"}
				""";

		List<UUID> leadIds = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			String responseBody =
					mockMvc
							.perform(
									post("/api/leads")
											.with(request -> {
												request.setRemoteAddr("10.0.0.5");
												return request;
											})
											.contentType(MediaType.APPLICATION_JSON)
											.content(body))
							.andExpect(status().isCreated())
							.andReturn()
							.getResponse()
							.getContentAsString();
			leadIds.add(extractId(responseBody));
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

		for (UUID leadId : leadIds) {
			awaitForwardOutcome(leadId);
		}
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
		awaitForwardOutcome(leadId);
		Lead saved = leadRepository.findById(leadId).orElseThrow();
		assertThat(saved.getForwardedAt()).isNull();
		assertThat(saved.getForwardAttempts()).isEqualTo(1);
	}

	/**
	 * TASK-015 review r1, BLOQUEADOR 1: a phone number formatted exactly like the public site's own
	 * placeholder ({@code dc/src/pages/Contact.js:232}, {@code "+351 912 345 678"}) — which {@code
	 * dcbo-backend}'s stricter pattern would have rejected with 400 during forwarding, silently
	 * losing the lead — is accepted here, normalized (spaces stripped) before being persisted and
	 * forwarded, and successfully reaches {@code dcbo-backend}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void acceptsAPhoneNumberFormattedLikeTheSitesOwnPlaceholderAndNormalizesItBeforeForwarding() throws Exception {
		dcboBackend
				.expect(requestTo("http://localhost:8080/internal/leads"))
				.andExpect(content().string(Matchers.containsString("\"telefone\":\"+351912345678\"")))
				.andRespond(withSuccess());

		String responseBody =
				mockMvc
						.perform(
								post("/api/leads")
										.with(request -> {
											request.setRemoteAddr("10.0.0.10");
											return request;
										})
										.contentType(MediaType.APPLICATION_JSON)
										.content("""
												{"nome":"Site Publico","telefone":"+351 912 345 678"}
												"""))
						.andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString();

		UUID leadId = extractId(responseBody);
		awaitForwardOutcome(leadId);
		dcboBackend.verify();

		Lead saved = leadRepository.findById(leadId).orElseThrow();
		assertThat(saved.getTelefone()).isEqualTo("+351912345678");
		assertThat(saved.getForwardedAt()).isNotNull();
	}

	/**
	 * TASK-015 review r1, BLOQUEADOR 1: a {@code nome} shorter than {@code dcbo-backend}'s own
	 * minimum (2 characters) is rejected here with 400, instead of being accepted with 201 and then
	 * silently lost when the forward is rejected.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsANameShorterThanDcboBackendAccepts() throws Exception {
		mockMvc
				.perform(
						post("/api/leads")
								.with(request -> {
									request.setRemoteAddr("10.0.0.11");
									return request;
								})
								.contentType(MediaType.APPLICATION_JSON)
								.content("""
										{"nome":"A","telefone":"913456789"}
										"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(Matchers.containsString("nome")));

		assertThat(leadRepository.count()).isZero();
	}

	/**
	 * TASK-015 review r1, BLOQUEADOR 1: a {@code nome} longer than {@code dcbo-backend}'s own
	 * maximum (100 characters) is rejected here with 400, instead of being accepted with 201 and
	 * then silently lost when the forward is rejected.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsANameLongerThanDcboBackendAccepts() throws Exception {
		String longName = "A".repeat(150);

		mockMvc
				.perform(
						post("/api/leads")
								.with(request -> {
									request.setRemoteAddr("10.0.0.12");
									return request;
								})
								.contentType(MediaType.APPLICATION_JSON)
								.content("{\"nome\":\"" + longName + "\",\"telefone\":\"913456789\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(Matchers.containsString("nome")));

		assertThat(leadRepository.count()).isZero();
	}

	/**
	 * TASK-015 review r1, BLOQUEADOR 1: a {@code mensagem} that fits under {@code dcbo-backend}'s
	 * 1000-character limit <b>before</b> sanitizing, but grows past it once HTML-entity escaping
	 * expands every {@code /} into the six characters {@code &#x2F;}, is rejected here with 400 —
	 * proving the length bound is now checked after sanitizing, not before.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsAMensagemThatOnlyExceedsTheLimitAfterSanitizationEscaping() throws Exception {
		String rawMensagem = "/".repeat(200);

		mockMvc
				.perform(
						post("/api/leads")
								.with(request -> {
									request.setRemoteAddr("10.0.0.13");
									return request;
								})
								.contentType(MediaType.APPLICATION_JSON)
								.content(
										"{\"nome\":\"Mensagem Grande\",\"telefone\":\"913456789\",\"mensagem\":\""
												+ rawMensagem
												+ "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(Matchers.containsString("mensagem")));

		assertThat(leadRepository.count()).isZero();
	}

	/**
	 * TASK-015 review r1, IMPORTANTE 2: an email whose quoted local part carries markup ({@code
	 * "<svg/onload=alert(1)>"@x.pt}) satisfies {@code @Email}'s own format check (quoted local
	 * parts are valid per RFC) but is rejected here with 400, closing the one XSS vector {@code
	 * @Email} alone lets through.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsAnEmailContainingMarkupCharactersInAQuotedLocalPart() throws Exception {
		String body =
				"""
				{"nome":"Atacante Email","telefone":"913456789",
				 "email":"\\\"<svg/onload=alert(1)>\\\"@x.pt"}
				""";

		mockMvc
				.perform(
						post("/api/leads")
								.with(request -> {
									request.setRemoteAddr("10.0.0.14");
									return request;
								})
								.contentType(MediaType.APPLICATION_JSON)
								.content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(Matchers.containsString("email")));

		assertThat(leadRepository.count()).isZero();
	}

	/**
	 * TASK-015 review r1, IMPORTANTE 2: a {@code telefone} carrying an HTML injection payload
	 * instead of digits is rejected with 400 by the same pattern that fixes BLOQUEADOR 1 — a value
	 * shaped like this can never match the phone format, so it is rejected before it could ever be
	 * stored or forwarded raw.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsATelefoneContainingMarkupInsteadOfDigits() throws Exception {
		mockMvc
				.perform(
						post("/api/leads")
								.with(request -> {
									request.setRemoteAddr("10.0.0.15");
									return request;
								})
								.contentType(MediaType.APPLICATION_JSON)
								.content("""
										{"nome":"Atacante Telefone","telefone":"<img src=x onerror=alert(1)>"}
										"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(Matchers.containsString("telefone")));

		assertThat(leadRepository.count()).isZero();
	}

	/**
	 * TASK-015 review r1, BLOQUEADOR 2: distinct visitors that all arrive through the same proxy
	 * address (the same {@code getRemoteAddr()}, as every visitor's would be on Render) but with
	 * distinct {@code X-Forwarded-For} values are rate-limited independently — none of the first 6
	 * distinct visitors is rejected, proving the limiter no longer treats the whole proxy as a
	 * single visitor.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void distinctVisitorsBehindTheSameProxyAddressAreRateLimitedIndependently() throws Exception {
		dcboBackend
				.expect(ExpectedCount.times(6), requestTo("http://localhost:8080/internal/leads"))
				.andRespond(withSuccess());

		String body = """
				{"nome":"Visitante Distinto","telefone":"913456789"}
				""";

		List<UUID> leadIds = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			String responseBody =
					mockMvc
							.perform(
									post("/api/leads")
											.with(request -> {
												request.setRemoteAddr("10.0.0.200");
												return request;
											})
											.header("X-Forwarded-For", "203.0.113." + i)
											.contentType(MediaType.APPLICATION_JSON)
											.content(body))
							.andExpect(status().isCreated())
							.andReturn()
							.getResponse()
							.getContentAsString();
			leadIds.add(extractId(responseBody));
		}

		for (UUID leadId : leadIds) {
			awaitForwardOutcome(leadId);
		}
		dcboBackend.verify();
	}

	/**
	 * TASK-015 review r1, IMPORTANTE 3: 5 CORS preflight {@code OPTIONS} requests from the same
	 * address, followed by 5 real {@code POST} submissions from that same address, all 5 {@code
	 * POST}s still succeed — proving the preflights never consumed any of the visitor's quota.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void preflightOptionsRequestsDoNotConsumeTheRateLimitQuota() throws Exception {
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(
					options("/api/leads")
							.with(request -> {
								request.setRemoteAddr("10.0.0.16");
								return request;
							}));
		}

		dcboBackend.expect(ExpectedCount.times(5), requestTo("http://localhost:8080/internal/leads")).andRespond(withSuccess());

		String body = """
				{"nome":"Depois De Preflights","telefone":"913456789"}
				""";

		List<UUID> leadIds = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			String responseBody =
					mockMvc
							.perform(
									post("/api/leads")
											.with(request -> {
												request.setRemoteAddr("10.0.0.16");
												return request;
											})
											.contentType(MediaType.APPLICATION_JSON)
											.content(body))
							.andExpect(status().isCreated())
							.andReturn()
							.getResponse()
							.getContentAsString();
			leadIds.add(extractId(responseBody));
		}

		for (UUID leadId : leadIds) {
			awaitForwardOutcome(leadId);
		}
	}

	/**
	 * TASK-015 review r1, IMPORTANTE 5: with {@code dcbo-backend} simulated as taking 1.5 s to
	 * respond, {@code POST /api/leads} still returns 201 in well under 1 s — proving the forward
	 * runs asynchronously, off the site visitor's own request thread, instead of blocking the
	 * response on it. The lead is still eventually forwarded, verified once {@link
	 * #awaitForwardOutcome(UUID)} confirms the background attempt finished.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void respondsBeforeTheSlowForwardingCallCompletesBecauseItRunsAsynchronously() throws Exception {
		ResponseCreator delayedSuccess =
				request -> {
					try {
						Thread.sleep(1500);
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
					}
					return withSuccess().createResponse(request);
				};
		dcboBackend.expect(requestTo("http://localhost:8080/internal/leads")).andRespond(delayedSuccess);

		Instant start = Instant.now();
		String responseBody =
				mockMvc
						.perform(
								post("/api/leads")
										.with(request -> {
											request.setRemoteAddr("10.0.0.17");
											return request;
										})
										.contentType(MediaType.APPLICATION_JSON)
										.content("""
												{"nome":"Assincrono Teste","telefone":"913456789"}
												"""))
						.andExpect(status().isCreated())
						.andReturn()
						.getResponse()
						.getContentAsString();
		long elapsedMs = Duration.between(start, Instant.now()).toMillis();

		assertThat(elapsedMs).isLessThan(1000);

		UUID leadId = extractId(responseBody);
		awaitForwardOutcome(leadId);
		Lead saved = leadRepository.findById(leadId).orElseThrow();
		assertThat(saved.getForwardedAt()).isNotNull();
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

	/**
	 * Polls the given lead until {@code LeadForwarder}'s asynchronous forwarding attempt (review
	 * r1, IMPORTANTE 5) has recorded some outcome — {@code forwardedAt} set, or {@code
	 * forwardAttempts} incremented — so a test can deterministically assert on that outcome, and so
	 * this test class never finishes with a forward attempt still racing in the background against
	 * whatever {@link #dcboBackend} the next test's {@link #setUp()} rebinds.
	 *
	 * @param leadId the lead whose forwarding outcome to await
	 */
	private void awaitForwardOutcome(UUID leadId) {
		Instant deadline = Instant.now().plusSeconds(5);
		while (Instant.now().isBefore(deadline)) {
			Lead reloaded = leadRepository.findById(leadId).orElseThrow();
			if (reloaded.getForwardedAt() != null || reloaded.getForwardAttempts() > 0) {
				return;
			}
			sleepBriefly();
		}
		throw new AssertionError("Lead " + leadId + " forwarding outcome was not recorded within the timeout");
	}

	/**
	 * Sleeps for a short, fixed interval while polling in {@link #awaitForwardOutcome(UUID)},
	 * restoring the interrupt flag instead of swallowing it if interrupted.
	 */
	private static void sleepBriefly() {
		try {
			Thread.sleep(50);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}
}
