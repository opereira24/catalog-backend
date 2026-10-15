package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.lead.LeadOrigin;
import pt.diamondcars.catalogbackend.domain.lead.LeadRepository;
import pt.diamondcars.catalogbackend.domain.lead.LeadStatus;
import pt.diamondcars.catalogbackend.domain.notification.NotificationRepository;
import pt.diamondcars.catalogbackend.service.LeadService;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import pt.diamondcars.catalogbackend.web.dto.LeadRequest;

/**
 * End-to-end tests of {@link PublicLeadController}, {@code LeadService}, {@code
 * NotificationService#createForNewLead} and {@link RateLimitInterceptor} through the real servlet
 * filter chain (TASK-015, TASK-006), using {@link MockMvc} against a real PostgreSQL container
 * ({@link AbstractPostgresIntegrationTest}). Same context configuration as the back-office tests (no
 * {@code @MockitoBean}, {@code @Import} or extra properties), so the cached context is shared.
 *
 * <p>Since TASK-006 a lead is not forwarded anywhere: it is saved with its back-office notification
 * in one transaction. The public contract (URL, body, validation, sanitizing, rate limit, {@code
 * 201 {"id": ...}}) is the one TASK-015 fixed, unchanged.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PublicLeadControllerTest extends AbstractPostgresIntegrationTest {

	@Autowired private MockMvc mockMvc;
	@Autowired private LeadRepository leadRepository;
	@Autowired private CarRepository carRepository;
	@Autowired private NotificationRepository notificationRepository;
	@Autowired private LeadService leadService;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private EntityManagerFactory entityManagerFactory;

	/**
	 * Clears every notification, lead and car written by a previous test (the container is shared by
	 * the whole JVM, with no per-test rollback).
	 */
	@BeforeEach
	void setUp() {
		notificationRepository.deleteAllInBatch();
		leadRepository.deleteAllInBatch();
		carRepository.deleteAll();
	}

	/**
	 * A valid payload with a {@code carroId} that matches no car is persisted with {@code origem =
	 * website} (decided by the presence of {@code carroId}).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void createsALeadWithCarroId() throws Exception {
		String body = """
				{"nome":"Joao Cliente","telefone":"913456789","email":"joao@example.com",
				 "carroId":"%s","carroMarca":"BMW","carroModelo":"320d"}
				""".formatted(UUID.randomUUID());

		UUID leadId = createLead("10.0.0.1", body);

		assertThat(leadRepository.findById(leadId).orElseThrow().getOrigem()).isEqualTo(LeadOrigin.WEBSITE);
	}

	/**
	 * A valid payload without {@code carroId} (general contact) is persisted with {@code origem =
	 * website-contacto}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void createsAGeneralContactLeadWithoutACar() throws Exception {
		UUID leadId = createLead("10.0.0.2", """
				{"nome":"Maria Interessada","telefone":"914567890"}
				""");

		assertThat(leadRepository.findById(leadId).orElseThrow().getOrigem()).isEqualTo(LeadOrigin.WEBSITE_CONTACTO);
	}

	/**
	 * Missing {@code telefone} is rejected with 400 naming the field, and a malformed {@code email} is
	 * also rejected with 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsMissingTelefoneAndMalformedEmail() throws Exception {
		mockMvc
				.perform(postLead("10.0.0.3", """
						{"nome":"Sem Telefone"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(Matchers.containsString("telefone")));

		mockMvc
				.perform(postLead("10.0.0.3", """
						{"nome":"Email Invalido","telefone":"913456789","email":"nao-e-email"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(Matchers.containsString("email")));

		assertThat(leadRepository.count()).isZero();
		assertThat(notificationRepository.count()).isZero();
	}

	/**
	 * A {@code mensagem} containing {@code <script>alert(1)</script>} is persisted without the literal
	 * {@code <script} substring, in {@code leads.mensagem} (never in {@code notas}, TASK-006 AC C.2).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void sanitizesAMensagemContainingAScriptTagAndKeepsItInMensagem() throws Exception {
		UUID leadId = createLead("10.0.0.4", """
				{"nome":"Atacante","telefone":"913456789","mensagem":"<script>alert(1)</script>"}
				""");

		Lead saved = leadRepository.findById(leadId).orElseThrow();
		assertThat(saved.getMensagem()).isNotBlank().doesNotContain("<script");
		assertThat(saved.getNotas()).isNull();
	}

	/**
	 * 5 submissions from the same IP within the configured window succeed, and the 6th is rejected
	 * with 429.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsTheSixthSubmissionFromTheSameIpWithinTheWindow() throws Exception {
		String body = """
				{"nome":"Rate Limitado","telefone":"913456789"}
				""";
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(postLead("10.0.0.5", body)).andExpect(status().isCreated());
		}

		mockMvc.perform(postLead("10.0.0.5", body)).andExpect(status().isTooManyRequests());
	}

	/**
	 * TASK-015 review r1, BLOQUEADOR 1: a phone number formatted like the public site's own
	 * placeholder ({@code dc/src/pages/Contact.js:232}, {@code "+351 912 345 678"}) is accepted and
	 * normalized (spaces stripped) before being persisted.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void acceptsAPhoneNumberFormattedLikeTheSitesOwnPlaceholderAndNormalizesIt() throws Exception {
		UUID leadId = createLead("10.0.0.10", """
				{"nome":"Site Publico","telefone":"+351 912 345 678"}
				""");

		assertThat(leadRepository.findById(leadId).orElseThrow().getTelefone()).isEqualTo("+351912345678");
	}

	/**
	 * TASK-015 review r1, BLOQUEADOR 1: a {@code nome} shorter than 2 characters is rejected with 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsANameShorterThanTwoCharacters() throws Exception {
		mockMvc
				.perform(postLead("10.0.0.11", """
						{"nome":"A","telefone":"913456789"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(Matchers.containsString("nome")));

		assertThat(leadRepository.count()).isZero();
	}

	/**
	 * TASK-015 review r1, BLOQUEADOR 1: a {@code nome} longer than 100 characters is rejected with 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsANameLongerThanOneHundredCharacters() throws Exception {
		mockMvc
				.perform(postLead("10.0.0.12", "{\"nome\":\"" + "A".repeat(150) + "\",\"telefone\":\"913456789\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(Matchers.containsString("nome")));

		assertThat(leadRepository.count()).isZero();
	}

	/**
	 * TASK-015 review r1, BLOQUEADOR 1: a {@code mensagem} that fits the 1000-character limit before
	 * sanitizing, but grows past it once HTML-entity escaping expands every {@code /} into {@code
	 * &#x2F;}, is rejected with 400: the bound is checked after sanitizing.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsAMensagemThatOnlyExceedsTheLimitAfterSanitizationEscaping() throws Exception {
		mockMvc
				.perform(postLead(
						"10.0.0.13",
						"{\"nome\":\"Mensagem Grande\",\"telefone\":\"913456789\",\"mensagem\":\"" + "/".repeat(200) + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(Matchers.containsString("mensagem")));

		assertThat(leadRepository.count()).isZero();
	}

	/**
	 * TASK-015 review r1, IMPORTANTE 2: an email whose quoted local part carries markup satisfies
	 * {@code @Email} but is rejected with 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsAnEmailContainingMarkupCharactersInAQuotedLocalPart() throws Exception {
		mockMvc
				.perform(postLead("10.0.0.14", """
						{"nome":"Atacante Email","telefone":"913456789",
						 "email":"\\\"<svg/onload=alert(1)>\\\"@x.pt"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(Matchers.containsString("email")));

		assertThat(leadRepository.count()).isZero();
	}

	/**
	 * TASK-015 review r1, IMPORTANTE 2: a {@code telefone} carrying an HTML injection payload instead
	 * of digits is rejected with 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsATelefoneContainingMarkupInsteadOfDigits() throws Exception {
		mockMvc
				.perform(postLead("10.0.0.15", """
						{"nome":"Atacante Telefone","telefone":"<img src=x onerror=alert(1)>"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(Matchers.containsString("telefone")));

		assertThat(leadRepository.count()).isZero();
	}

	/**
	 * TASK-015 review r1, BLOQUEADOR 2: distinct visitors behind the same proxy address but with
	 * distinct {@code X-Forwarded-For} values are rate-limited independently.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void distinctVisitorsBehindTheSameProxyAddressAreRateLimitedIndependently() throws Exception {
		String body = """
				{"nome":"Visitante Distinto","telefone":"913456789"}
				""";
		for (int i = 0; i < 6; i++) {
			mockMvc
					.perform(postLead("10.0.0.200", body).header("X-Forwarded-For", "203.0.113." + i))
					.andExpect(status().isCreated());
		}
	}

	/**
	 * TASK-015 review r2, IMPORTANTE 1: a client behind a trusted proxy cannot bypass the limit by
	 * rotating a fabricated left-most {@code X-Forwarded-For} entry; only the right-most entry (the
	 * one the trusted proxy appended) resolves the bucket.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsTheSixthSubmissionEvenWhenTheLeftMostForwardedForEntryIsSpoofedAndRotates() throws Exception {
		String body = """
				{"nome":"Spoofer Rate Limit","telefone":"913456789"}
				""";
		for (int i = 0; i < 5; i++) {
			mockMvc
					.perform(postLead("10.0.0.201", body).header("X-Forwarded-For", "198.51.100." + i + ", 203.0.113.99"))
					.andExpect(status().isCreated());
		}

		mockMvc
				.perform(postLead("10.0.0.201", body).header("X-Forwarded-For", "198.51.100.99, 203.0.113.99"))
				.andExpect(status().isTooManyRequests());
	}

	/**
	 * TASK-015 review r1, IMPORTANTE 3: CORS preflight {@code OPTIONS} requests never consume the
	 * visitor's quota.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void preflightOptionsRequestsDoNotConsumeTheRateLimitQuota() throws Exception {
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(options("/api/leads").with(request -> {
				request.setRemoteAddr("10.0.0.16");
				return request;
			}));
		}
		String body = """
				{"nome":"Depois De Preflights","telefone":"913456789"}
				""";
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(postLead("10.0.0.16", body)).andExpect(status().isCreated());
		}
	}

	/**
	 * TASK-001, AC D.6 (a): a {@code carroId} of a car that exists links the lead to that car, with
	 * {@code status = ativo} and {@code origem = website}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void linksTheLeadToAnExistingCar() throws Exception {
		Car car = carRepository.saveAndFlush(aCar());

		UUID leadId = createLead("10.0.0.18", """
				{"nome":"Lead Com Carro","telefone":"913456789",
				 "carroId":"%s","carroMarca":"BMW","carroModelo":"320d"}
				""".formatted(car.getId()));

		assertThat(jdbcTemplate.queryForMap("SELECT car_id, status, origem FROM leads WHERE id = ?", leadId))
				.containsEntry("car_id", car.getId())
				.containsEntry("status", "ativo")
				.containsEntry("origem", "website");
	}

	/**
	 * TASK-001, AC D.6 (b): a {@code carroId} that matches no car still answers 201: the lead is saved
	 * without car, with its snapshot, {@code origem = website} and {@code status = ativo}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void savesTheLeadWithoutCarWhenCarroIdMatchesNoCar() throws Exception {
		UUID leadId = createLead("10.0.0.19", """
				{"nome":"Carro Desconhecido","telefone":"913456789",
				 "carroId":"%s","carroMarca":"Fiat","carroModelo":"Punto"}
				""".formatted(UUID.randomUUID()));

		Lead saved = leadRepository.findById(leadId).orElseThrow();
		assertThat(saved.getCar()).isNull();
		assertThat(saved.getCarroMarca()).isEqualTo("Fiat");
		assertThat(saved.getCarroModelo()).isEqualTo("Punto");
		assertThat(saved.getOrigem()).isEqualTo(LeadOrigin.WEBSITE);
		assertThat(saved.getStatus()).isEqualTo(LeadStatus.ATIVO);
	}

	// ---------------------------------------------------------------------------------------------
	// TASK-006, AC C.4: the lead and its notification, in one transaction
	// ---------------------------------------------------------------------------------------------

	/**
	 * A lead about a car: one row in {@code leads} ({@code status ativo}, {@code origem website}) and
	 * one in {@code notifications} with the 6 fields of AC C.3.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aLeadAboutACarCreatesExactlyOneLeadAndOneNotification() throws Exception {
		Car car = carRepository.saveAndFlush(aCar());

		UUID leadId = createLead("10.0.1.1", """
				{"nome":"Ana","telefone":"913456789","carroId":"%s","carroMarca":"BMW","carroModelo":"X1"}
				""".formatted(car.getId()));

		assertThat(jdbcTemplate.queryForList("SELECT id, status, origem FROM leads"))
				.containsExactly(Map.of("id", leadId, "status", "ativo", "origem", "website"));
		assertThat(jdbcTemplate.queryForList(
						"SELECT tipo, titulo, prioridade, read, lead_id, mensagem FROM notifications"))
				.containsExactly(Map.of(
						"tipo", "novo_lead",
						"titulo", "Novo Lead",
						"prioridade", "media",
						"read", false,
						"lead_id", leadId,
						"mensagem", "Novo lead de Ana sobre BMW X1"));
	}

	/**
	 * A general contact with {@code carroMarca: ""} (what {@code dc} sends) gets a message with no
	 * {@code " sobre "} part at all; {@code dcbo-backend} wrote {@code "Novo lead de Ana sobre  "}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aGeneralContactWithAnEmptyCarroMarcaGetsTheMessageWithoutCar() throws Exception {
		createLead("10.0.1.2", """
				{"nome":"Ana","telefone":"913456789","carroMarca":""}
				""");

		assertThat(jdbcTemplate.queryForList("SELECT mensagem FROM notifications", String.class))
				.containsExactly("Novo lead de Ana");
	}

	/**
	 * A brand without a model gives {@code "sobre BMW"} ({@code dcbo-backend} wrote {@code "sobre BMW
	 * null"}), and a model without a brand {@code "sobre X1"}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void theNotificationMessageOnlyJoinsTheNonBlankCarParts() throws Exception {
		createLead("10.0.1.3", """
				{"nome":"Ana","telefone":"913456789","carroMarca":"BMW"}
				""");
		createLead("10.0.1.3", """
				{"nome":"Rui","telefone":"913456789","carroMarca":"  ","carroModelo":"X1"}
				""");

		assertThat(jdbcTemplate.queryForList("SELECT mensagem FROM notifications", String.class))
				.containsExactlyInAnyOrder("Novo lead de Ana sobre BMW", "Novo lead de Rui sobre X1");
	}

	/**
	 * The notification uses the sanitized {@code nome}, the same value stored in the lead.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void theNotificationMessageUsesTheSanitizedNome() throws Exception {
		UUID leadId = createLead("10.0.1.4", """
				{"nome":"<b>Ana</b>","telefone":"913456789"}
				""");

		String storedNome = leadRepository.findById(leadId).orElseThrow().getNome();
		assertThat(storedNome).doesNotContain("<b>");
		assertThat(jdbcTemplate.queryForList("SELECT mensagem FROM notifications", String.class))
				.containsExactly("Novo lead de " + storedNome);
	}

	/**
	 * Atomicity: {@code createFromWebsite} inside a transaction that ends in rollback leaves zero leads
	 * and zero notifications. Kills a {@code REQUIRES_NEW} on {@code createForNewLead} (the notification
	 * would commit on its own, or fail on the foreign key of the lead not yet committed) and any
	 * {@code @TransactionalEventListener}/{@code @Async} path.
	 */
	@Test
	void aRollbackOfTheCallersTransactionLeavesNeitherTheLeadNorTheNotification() {
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			leadService.createFromWebsite(new LeadRequest("Ana", "913456789", null, null, null, "BMW", "X1"));
			transaction.setRollbackOnly();
		});

		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM leads", Long.class)).isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications", Long.class)).isZero();
	}

	/**
	 * Cost: {@code POST /api/leads} with a car is exactly 3 statements: {@code lockKeyShare} of the
	 * car, {@code INSERT} of the lead, {@code INSERT} of the notification. No token, so no {@code
	 * ActiveUserInterceptor} query.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aLeadAboutACarCostsExactlyThreeStatements() throws Exception {
		Car car = carRepository.saveAndFlush(aCar());
		String body = """
				{"nome":"Ana","telefone":"913456789","carroId":"%s","carroMarca":"BMW","carroModelo":"X1"}
				""".formatted(car.getId());

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();
		long statements;
		try {
			mockMvc.perform(postLead("10.0.1.5", body)).andExpect(status().isCreated());
			statements = statistics.getPrepareStatementCount();
		} finally {
			statistics.setStatisticsEnabled(false);
		}

		assertThat(statements).isEqualTo(3);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications", Long.class)).isEqualTo(1);
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------

	private static Car aCar() {
		return Car.builder()
				.marca("BMW")
				.modelo("320d")
				.ano(2020)
				.preco(new BigDecimal("25000.00"))
				.km(50000)
				.cor("Preto")
				.combustivel("Diesel")
				.transmissao("Manual")
				.origem("Nacional")
				.build();
	}

	private static MockHttpServletRequestBuilder postLead(String remoteAddr, String body) {
		return post("/api/leads")
				.with(request -> {
					request.setRemoteAddr(remoteAddr);
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content(body);
	}

	private UUID createLead(String remoteAddr, String body) throws Exception {
		String responseBody = mockMvc
				.perform(postLead(remoteAddr, body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").exists())
				.andReturn()
				.getResponse()
				.getContentAsString();
		return UUID.fromString(responseBody.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1"));
	}
}
