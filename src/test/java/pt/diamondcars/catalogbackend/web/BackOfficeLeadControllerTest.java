package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.lead.LeadOrigin;
import pt.diamondcars.catalogbackend.domain.lead.LeadRepository;
import pt.diamondcars.catalogbackend.domain.lead.LeadStatus;
import pt.diamondcars.catalogbackend.domain.notification.Notification;
import pt.diamondcars.catalogbackend.domain.notification.NotificationRepository;
import pt.diamondcars.catalogbackend.domain.user.AppUser;
import pt.diamondcars.catalogbackend.domain.user.AppUserRepository;
import pt.diamondcars.catalogbackend.domain.user.AppUserRole;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end tests of {@link BackOfficeLeadController}, {@code BackOfficeLeadService} and {@link
 * ApiExceptionHandler} through the real filter chain (TASK-006, AC D.2, E.1, E.2, E.4, F.1), with
 * {@link MockMvc} against the shared PostgreSQL container. Same context configuration as {@code
 * TransactionControllerTest} (no {@code @MockitoBean}, {@code @Import} or extra properties), so the
 * cached context is reused. The races of the same endpoints are in {@code
 * service/BackOfficeLeadConcurrencyTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class BackOfficeLeadControllerTest extends AbstractPostgresIntegrationTest {

	private static final String PATH = "/api/backoffice/leads";
	private static final String INACTIVE_SUBJECT = "auth0|inactive-leads-test";

	/** The 15 keys of AC E.2. A literal, so a component added or renamed by accident fails here. */
	private static final List<String> LEAD_KEYS = List.of(
			"id", "nome", "email", "telefone", "mensagem", "notas", "carroId", "carroMarca", "carroModelo",
			"carroPreco", "followUpDate", "status", "origem", "createdAt", "updatedAt");

	/** Fixed ids in the order they are inserted (TASK-013): the heap order of a three-way tie. */
	private static final List<String> ASCENDING_IDS = List.of(
			"00000000-0000-0000-0000-000000000001",
			"00000000-0000-0000-0000-000000000002",
			"00000000-0000-0000-0000-000000000003");

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private LeadRepository leadRepository;
	@Autowired private CarRepository carRepository;
	@Autowired private NotificationRepository notificationRepository;
	@Autowired private AppUserRepository appUserRepository;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private EntityManagerFactory entityManagerFactory;

	@Value("${app.leads.rate-limit.max-requests}")
	private int rateLimitMaxRequests;

	/** Clears every row this class writes (shared container, no per-test rollback). */
	@BeforeEach
	void cleanDatabase() {
		notificationRepository.deleteAllInBatch();
		leadRepository.deleteAllInBatch();
		carRepository.deleteAllInBatch();
	}

	@AfterEach
	void removeTheInactiveProfile() {
		appUserRepository.findAll().stream()
				.filter(user -> INACTIVE_SUBJECT.equals(user.getAuthSubject()))
				.forEach(appUserRepository::delete);
	}

	// ---------------------------------------------------------------------------------------------
	// AC E.1: authorization
	// ---------------------------------------------------------------------------------------------

	/** @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void withoutATokenEveryRouteIs401() throws Exception {
		UUID id = UUID.randomUUID();
		for (MockHttpServletRequestBuilder request : List.of(
				get(PATH), get(PATH + "/{id}", id), postJson(validBody()), putJson(id, validBody()), delete(PATH + "/{id}", id))) {
			mockMvc.perform(request).andExpect(status().isUnauthorized());
		}
	}

	/**
	 * A valid token without a role (what Spring Security 7 builds for an empty roles claim) is 403 on
	 * reads and writes.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aTokenWithoutARoleIsForbidden() throws Exception {
		Lead lead = aLead().build();
		leadRepository.saveAndFlush(lead);

		mockMvc.perform(get(PATH).with(withoutRole()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Autenticado, mas sem a role exigida para esta operacao"));
		mockMvc.perform(delete(PATH + "/{id}", lead.getId()).with(withoutRole())).andExpect(status().isForbidden());
		assertThat(leadRepository.existsById(lead.getId())).isTrue();
	}

	/** @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void anInactiveUserIsForbidden() throws Exception {
		appUserRepository.saveAndFlush(AppUser.builder()
				.authSubject(INACTIVE_SUBJECT)
				.email("inativo@example.com")
				.name("Inativo")
				.role(AppUserRole.ADMIN)
				.active(false)
				.build());

		mockMvc.perform(get(PATH).with(jwt().jwt(token -> token.subject(INACTIVE_SUBJECT))
						.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Conta desativada: " + INACTIVE_SUBJECT));
	}

	/**
	 * Both roles do everything, delete included (parity with {@code dcbo-backend}).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void userAndAdminCanCreateReadEditAndDelete() throws Exception {
		for (RequestPostProcessor role : List.of(user(), admin())) {
			String location = mockMvc.perform(postJson(validBody()).with(role))
					.andExpect(status().isCreated())
					.andReturn().getResponse().getHeader("Location");
			mockMvc.perform(get(location).with(role)).andExpect(status().isOk());
			mockMvc.perform(get(PATH).with(role)).andExpect(status().isOk());
			mockMvc.perform(put(location).with(role).contentType(MediaType.APPLICATION_JSON).content(json(validBody())))
					.andExpect(status().isOk());
			mockMvc.perform(delete(location).with(role)).andExpect(status().isNoContent());
		}
	}

	// ---------------------------------------------------------------------------------------------
	// AC E.2: listing
	// ---------------------------------------------------------------------------------------------

	/**
	 * The listing is a {@code PagedModel} of the 15 keys, newest first by default, 50 per page.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listsNewestFirstWithExactlyTheFifteenKeys() throws Exception {
		Lead older = leadRepository.saveAndFlush(aLead().nome("Antigo").build());
		Lead newer = leadRepository.saveAndFlush(aLead().nome("Recente").build());
		setCreatedAt(older.getId(), "2026-01-01T10:00:00Z");
		setCreatedAt(newer.getId(), "2026-02-01T10:00:00Z");

		JsonNode listing = getJson(get(PATH));

		assertThat(listing.get("page").get("size").asInt()).isEqualTo(50);
		assertThat(ids(listing)).containsExactly(newer.getId().toString(), older.getId().toString());
		assertThat(new ArrayList<>(listing.get("content").get(0).propertyNames()))
				.containsExactlyInAnyOrderElementsOf(LEAD_KEYS);
	}

	/**
	 * TASK-013 convention: three leads tied on the requested sort come back {@code id} DESC across
	 * pages (without the tie-breaker, the heap order {@code 01, 02, 03}).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aClientRequestedSortHasIdAsLastTiebreaker() throws Exception {
		for (String id : ASCENDING_IDS) {
			jdbcTemplate.update(
					"INSERT INTO leads (id, nome, telefone, created_at, updated_at) VALUES (?::uuid, 'Igual', '912345678', ?, ?)",
					id, Timestamp.from(Instant.parse("2026-03-10T09:00:00Z")), Timestamp.from(Instant.parse("2026-03-10T09:00:00Z")));
		}
		jdbcTemplate.execute("ANALYZE leads");

		List<String> paged = new ArrayList<>();
		for (int page = 0; page < ASCENDING_IDS.size(); page++) {
			paged.addAll(ids(getJson(get(PATH).param("sort", "nome,asc").param("size", "1").param("page", String.valueOf(page)))));
		}

		assertThat(paged).containsExactlyElementsOf(ASCENDING_IDS.reversed());
	}

	/**
	 * {@code ?status=} takes only the stored lower-case values; the Java constant name and unknown
	 * values are 400; empty or blank means no filter. A {@code Converter} bean would let {@code
	 * ATIVO} through (TASK-005).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void theStatusFilterOnlyAcceptsTheStoredLowerCaseValues() throws Exception {
		Lead ativo = leadRepository.saveAndFlush(aLead().status(LeadStatus.ATIVO).build());
		leadRepository.saveAndFlush(aLead().status(LeadStatus.TEST_DRIVE_MARCADO).build());

		assertThat(ids(getJson(get(PATH).param("status", "ativo")))).containsExactly(ativo.getId().toString());
		assertThat(ids(getJson(get(PATH).param("status", "test_drive_marcado")))).hasSize(1);
		assertThat(ids(getJson(get(PATH).param("status", "")))).hasSize(2);
		assertThat(ids(getJson(get(PATH).param("status", "  ")))).hasSize(2);
		for (String invalid : List.of("ATIVO", "TEST_DRIVE_MARCADO", "xyz")) {
			mockMvc.perform(get(PATH).param("status", invalid).with(user()))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value("Parametro 'status' com valor invalido"));
		}
	}

	/**
	 * {@code ?carroId=} filters by car, combines with {@code ?status=}; an invalid UUID is 400, empty
	 * means no filter.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void filtersByCarroIdAndCombinesWithStatus() throws Exception {
		Car car = carRepository.saveAndFlush(aCar());
		Lead ofCarAtivo = leadRepository.saveAndFlush(aLead().car(car).status(LeadStatus.ATIVO).build());
		leadRepository.saveAndFlush(aLead().car(car).status(LeadStatus.VENDIDO).build());
		leadRepository.saveAndFlush(aLead().status(LeadStatus.ATIVO).build());

		assertThat(ids(getJson(get(PATH).param("carroId", car.getId().toString())))).hasSize(2);
		assertThat(ids(getJson(get(PATH).param("carroId", car.getId().toString()).param("status", "ativo"))))
				.containsExactly(ofCarAtivo.getId().toString());
		assertThat(ids(getJson(get(PATH).param("carroId", "")))).hasSize(3);
		mockMvc.perform(get(PATH).param("carroId", "nao-e-uuid").with(user()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Parametro 'carroId' com valor invalido"));
	}

	/**
	 * {@code sort} only takes scalar properties of {@code Lead}: {@code car.marca} (a join over the
	 * {@code LAZY} car) and unknown properties are 400 before any query.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void sortOnlyAcceptsTheScalarProperties() throws Exception {
		for (String property : List.of("nome", "email", "telefone", "status", "origem", "followUpDate", "carroMarca",
				"carroModelo", "carroPreco", "createdAt", "updatedAt", "id")) {
			mockMvc.perform(get(PATH).param("sort", property + ",asc").with(user())).andExpect(status().isOk());
		}
		for (String property : List.of("car.marca", "car", "mensagem", "xpto")) {
			mockMvc.perform(get(PATH).param("sort", property).with(user()))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value("Campo de ordenacao desconhecido: " + property));
		}
	}

	/**
	 * AC D.2: a lead with only the columns V1 had and the V2 defaults (a lead of the site never
	 * forwarded) is listed, also with {@code ?status=ativo}: the listing has no predicate beyond the
	 * filters asked for.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aLeadWithTheShapeOfV1IsListed() throws Exception {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO leads (id, nome, telefone, mensagem, carro_marca, carro_modelo, origem, created_at) "
						+ "VALUES (?, 'Rui', '922222222', 'Ainda disponivel?', 'Fiat', 'Punto', 'website', "
						+ "'2026-03-02T00:00:00Z')",
				id);

		JsonNode listed = getJson(get(PATH)).get("content").get(0);
		assertThat(listed.get("id").asString()).isEqualTo(id.toString());
		assertThat(listed.get("status").asString()).isEqualTo("ativo");
		assertThat(listed.get("mensagem").asString()).isEqualTo("Ainda disponivel?");
		assertThat(ids(getJson(get(PATH).param("status", "ativo")))).containsExactly(id.toString());
	}

	/**
	 * AC E.4: 50 leads with a car are at most 3 statements (page, {@code count}, {@code
	 * ActiveUserInterceptor}); the car is never loaded.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listingFiftyLeadsWithCarRunsAtMostThreeStatements() throws Exception {
		Car car = carRepository.saveAndFlush(aCar());
		List<Lead> leads = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			leads.add(aLead().car(car).build());
		}
		leadRepository.saveAllAndFlush(leads);

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();
		JsonNode listing;
		long statements;
		try {
			listing = getJson(get(PATH));
			statements = statistics.getPrepareStatementCount();
		} finally {
			statistics.setStatisticsEnabled(false);
		}

		assertThat(listing.get("content")).hasSize(50);
		listing.get("content").forEach(lead -> assertThat(lead.get("carroId").asString()).isEqualTo(car.getId().toString()));
		assertThat(statements).isPositive().isLessThanOrEqualTo(3);
	}

	// ---------------------------------------------------------------------------------------------
	// AC E.2: get, create, replace, delete
	// ---------------------------------------------------------------------------------------------

	/** @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void getAnswers404ForAnUnknownIdAnd400ForANonUuid() throws Exception {
		UUID unknown = UUID.randomUUID();
		mockMvc.perform(get(PATH + "/{id}", unknown).with(user()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Lead nao encontrado: " + unknown));
		mockMvc.perform(get(PATH + "/nao-e-uuid").with(user()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Parametro 'id' com valor invalido"));
	}

	/**
	 * {@code POST}: 201 with {@code Location}, {@code origem backoffice} whatever the body says, the
	 * car and its snapshot from the body, no {@code mensagem} and no notification (only a lead of the
	 * site notifies). Unknown keys ({@code origem}, {@code mensagem}, {@code id}) have no effect.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void createsABackOfficeLeadWithoutNotification() throws Exception {
		Car car = carRepository.saveAndFlush(aCar());
		Map<String, Object> body = validBody();
		body.put("email", "ana@example.com");
		body.put("notas", "Quer retoma");
		body.put("status", "test_drive_marcado");
		body.put("followUpDate", "2026-11-02");
		body.put("carroId", car.getId());
		body.put("carroMarca", "Audi");
		body.put("carroModelo", "A4");
		body.put("carroPreco", new BigDecimal("28000.50"));
		body.put("origem", "website");
		body.put("mensagem", "ignorada");
		body.put("id", UUID.randomUUID());

		String response = mockMvc.perform(postJson(body).with(user()))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", startsWith(PATH + "/")))
				.andReturn().getResponse().getContentAsString();

		JsonNode created = objectMapper.readTree(response);
		UUID id = UUID.fromString(created.get("id").asString());
		assertThat(created.get("origem").asString()).isEqualTo("backoffice");
		assertThat(created.get("status").asString()).isEqualTo("test_drive_marcado");
		assertThat(created.get("followUpDate").asString()).isEqualTo("2026-11-02");
		assertThat(created.get("carroId").asString()).isEqualTo(car.getId().toString());
		assertThat(created.get("mensagem").isNull()).isTrue();
		assertThat(created.get("createdAt").isNull()).isFalse();
		assertThat(response).contains("\"carroPreco\":28000.50");
		assertThat(jdbcTemplate.queryForMap(
						"SELECT origem, mensagem, notas, carro_marca, carro_modelo, car_id FROM leads WHERE id = ?", id))
				.containsEntry("origem", "backoffice")
				.containsEntry("mensagem", null)
				.containsEntry("notas", "Quer retoma")
				.containsEntry("carro_marca", "Audi")
				.containsEntry("carro_modelo", "A4")
				.containsEntry("car_id", car.getId());
		assertThat(notificationRepository.count()).isZero();
	}

	/** @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void createWithAnUnknownCarIs404AndStoresNothing() throws Exception {
		UUID unknownCar = UUID.randomUUID();
		Map<String, Object> body = validBody();
		body.put("carroId", unknownCar);

		mockMvc.perform(postJson(body).with(user()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Carro nao encontrado: " + unknownCar));
		assertThat(leadRepository.count()).isZero();
	}

	/**
	 * Every rule of {@code BackOfficeLeadRequest} is a 400 naming the field, and nothing is stored.
	 * {@code followUpDate} out of {@code [0001-01-01, 9999-12-31]} included: unchecked, {@code
	 * -5000-01-01} came back as a 409 from PostgreSQL.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsEveryInvalidFieldWith400NamingIt() throws Exception {
		Map<String, Object> invalidValues = new LinkedHashMap<>();
		invalidValues.put("nome", "A");
		invalidValues.put("telefone", "12345");
		invalidValues.put("email", "nao-e-email");
		invalidValues.put("notas", "x".repeat(1001));
		invalidValues.put("status", "ATIVO");
		invalidValues.put("followUpDate", "-5000-01-01");
		invalidValues.put("carroMarca", "x".repeat(101));
		invalidValues.put("carroModelo", "x".repeat(101));
		invalidValues.put("carroPreco", new BigDecimal("22000.005"));
		for (Map.Entry<String, Object> invalid : invalidValues.entrySet()) {
			Map<String, Object> body = validBody();
			body.put(invalid.getKey(), invalid.getValue());
			mockMvc.perform(postJson(body).with(user()))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message", startsWith(invalid.getKey() + ": ")));
		}
		Map<String, Object> withoutStatus = validBody();
		withoutStatus.remove("status");
		mockMvc.perform(postJson(withoutStatus).with(user()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", startsWith("status: ")));
		assertThat(leadRepository.count()).isZero();
	}

	/**
	 * {@code followUpDate} accepts the two ends of the storable range and refuses one day beyond each.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void followUpDateAcceptsExactlyTheStorableRange() throws Exception {
		for (String accepted : List.of("0001-01-01", "9999-12-31")) {
			Map<String, Object> body = validBody();
			body.put("followUpDate", accepted);
			mockMvc.perform(postJson(body).with(user()))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.followUpDate").value(accepted));
		}
		for (String refused : List.of("0000-12-31", "+10000-01-01")) {
			Map<String, Object> body = validBody();
			body.put("followUpDate", refused);
			mockMvc.perform(postJson(body).with(user()))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message", startsWith("followUpDate: ")));
		}
	}

	/**
	 * {@code PUT} replaces exactly the 6 editable fields ({@code followUpDate} absent clears it) and
	 * never the car, its snapshot, {@code mensagem} or {@code origem}, even when the body sends them.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void replacesTheSixEditableFieldsAndNothingElse() throws Exception {
		Car car = carRepository.saveAndFlush(aCar());
		Lead lead = leadRepository.saveAndFlush(aLead()
				.email("antigo@example.com")
				.notas("Notas antigas")
				.followUpDate(LocalDate.of(2026, 10, 20))
				.mensagem("Mensagem do site")
				.car(car)
				.carroMarca("Audi")
				.carroModelo("A4")
				.carroPreco(new BigDecimal("28000.00"))
				.origem(LeadOrigin.WEBSITE)
				.build());
		Car otherCar = carRepository.saveAndFlush(aCar());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("nome", "Ana Maria");
		body.put("telefone", "+351 912345678");
		body.put("email", "novo@example.com");
		body.put("notas", "Notas novas");
		body.put("status", "negociacao");
		body.put("carroId", otherCar.getId());
		body.put("carroMarca", "BMW");
		body.put("carroModelo", "X1");
		body.put("carroPreco", 1);
		body.put("mensagem", "outra");
		body.put("origem", "backoffice");

		mockMvc.perform(putJson(lead.getId(), body).with(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nome").value("Ana Maria"))
				.andExpect(jsonPath("$.followUpDate").isEmpty())
				.andExpect(jsonPath("$.carroId").value(car.getId().toString()));

		assertThat(jdbcTemplate.queryForMap(
						"SELECT nome, telefone, email, notas, status, follow_up_date, car_id, carro_marca, carro_modelo, "
								+ "carro_preco, mensagem, origem FROM leads WHERE id = ?",
						lead.getId()))
				.containsEntry("nome", "Ana Maria")
				.containsEntry("telefone", "+351 912345678")
				.containsEntry("email", "novo@example.com")
				.containsEntry("notas", "Notas novas")
				.containsEntry("status", "negociacao")
				.containsEntry("follow_up_date", null)
				.containsEntry("car_id", car.getId())
				.containsEntry("carro_marca", "Audi")
				.containsEntry("carro_modelo", "A4")
				.containsEntry("carro_preco", new BigDecimal("28000.00"))
				.containsEntry("mensagem", "Mensagem do site")
				.containsEntry("origem", "website");
	}

	/** @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void putAnswers404ForAnUnknownLeadAnd400ForAnInvalidBody() throws Exception {
		UUID unknown = UUID.randomUUID();
		mockMvc.perform(putJson(unknown, validBody()).with(user()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Lead nao encontrado: " + unknown));

		Lead lead = leadRepository.saveAndFlush(aLead().build());
		Map<String, Object> body = validBody();
		body.put("status", "novo");
		mockMvc.perform(putJson(lead.getId(), body).with(user()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", startsWith("status: ")));
	}

	/**
	 * {@code DELETE} is 204 and takes the lead's notifications with it ({@code ON DELETE CASCADE});
	 * a second one is 404.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void deleteRemovesTheLeadAndItsNotifications() throws Exception {
		Lead lead = leadRepository.saveAndFlush(aLead().build());
		Lead other = leadRepository.saveAndFlush(aLead().build());
		notificationRepository.saveAndFlush(aNotification(lead));
		Notification ofOther = notificationRepository.saveAndFlush(aNotification(other));

		mockMvc.perform(delete(PATH + "/{id}", lead.getId()).with(user())).andExpect(status().isNoContent());

		assertThat(leadRepository.existsById(lead.getId())).isFalse();
		assertThat(notificationRepository.findAll()).extracting(Notification::getId).containsExactly(ofOther.getId());
		mockMvc.perform(delete(PATH + "/{id}", lead.getId()).with(user()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Lead nao encontrado: " + lead.getId()));
	}

	// ---------------------------------------------------------------------------------------------
	// AC F.1: the rate limit of POST /api/leads never applies to the back-office
	// ---------------------------------------------------------------------------------------------

	/**
	 * {@code max-requests + 1} authenticated requests from one IP to each back-office route of leads
	 * and notifications: none is 429. Positive control in the same test: the same IP is limited on
	 * {@code POST /api/leads} after {@code max-requests}, so the limiter was active.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void theBackOfficeRoutesAreNeverRateLimited() throws Exception {
		String ip = "10.0.6.1";
		Lead lead = leadRepository.saveAndFlush(aLead().build());
		Notification notification = notificationRepository.saveAndFlush(aNotification(lead));
		List<Supplier<MockHttpServletRequestBuilder>> routes = List.of(
				() -> postJson(validBody()),
				() -> get(PATH + "/{id}", lead.getId()),
				() -> putJson(lead.getId(), validBody()),
				() -> get("/api/backoffice/notifications"),
				() -> patch("/api/backoffice/notifications/{id}/read", notification.getId()),
				() -> post("/api/backoffice/notifications/read-all"),
				() -> delete("/api/backoffice/notifications/{id}", UUID.randomUUID()),
				() -> delete(PATH + "/{id}", UUID.randomUUID()));

		for (Supplier<MockHttpServletRequestBuilder> route : routes) {
			for (int i = 0; i <= rateLimitMaxRequests; i++) {
				int status = mockMvc.perform(route.get().with(fromIp(ip)).with(user())).andReturn().getResponse().getStatus();
				assertThat(status).as("request %d to a back-office route", i + 1).isNotEqualTo(429).isLessThan(500);
			}
		}

		String publicLead = """
				{"nome":"Controlo","telefone":"913456789"}
				""";
		for (int i = 0; i < rateLimitMaxRequests; i++) {
			mockMvc.perform(post("/api/leads").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content(publicLead))
					.andExpect(status().isCreated());
		}
		mockMvc.perform(post("/api/leads").with(fromIp(ip)).contentType(MediaType.APPLICATION_JSON).content(publicLead))
				.andExpect(status().isTooManyRequests());
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------

	private static Lead.LeadBuilder aLead() {
		return Lead.builder().nome("Ana").telefone("912345678").status(LeadStatus.ATIVO).origem(LeadOrigin.WEBSITE);
	}

	private static Notification aNotification(Lead lead) {
		return Notification.builder().tipo("novo_lead").titulo("Novo Lead").mensagem("Novo lead de Ana").lead(lead).build();
	}

	private static Car aCar() {
		return Car.builder()
				.marca("Audi")
				.modelo("A4")
				.ano(2020)
				.preco(new BigDecimal("28000.00"))
				.km(40000)
				.cor("Branco")
				.combustivel("Gasolina")
				.transmissao("Manual")
				.origem("Nacional")
				.build();
	}

	/** The smallest valid body: the 3 required fields. */
	private static Map<String, Object> validBody() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("nome", "Ana Cliente");
		body.put("telefone", "912345678");
		body.put("status", "ativo");
		return body;
	}

	private String json(Map<String, Object> body) {
		return objectMapper.writeValueAsString(body);
	}

	private MockHttpServletRequestBuilder postJson(Map<String, Object> body) {
		return post(PATH).contentType(MediaType.APPLICATION_JSON).content(json(body));
	}

	private MockHttpServletRequestBuilder putJson(UUID id, Map<String, Object> body) {
		return put(PATH + "/{id}", id).contentType(MediaType.APPLICATION_JSON).content(json(body));
	}

	private JsonNode getJson(MockHttpServletRequestBuilder request) throws Exception {
		String response = mockMvc.perform(request.with(user()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response);
	}

	private static List<String> ids(JsonNode listing) {
		List<String> ids = new ArrayList<>();
		listing.get("content").forEach(lead -> ids.add(lead.get("id").asString()));
		return ids;
	}

	private void setCreatedAt(UUID id, String instant) {
		jdbcTemplate.update("UPDATE leads SET created_at = ? WHERE id = ?", Timestamp.from(Instant.parse(instant)), id);
	}

	private static RequestPostProcessor fromIp(String ip) {
		return request -> {
			request.setRemoteAddr(ip);
			return request;
		};
	}

	private static RequestPostProcessor user() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private static RequestPostProcessor admin() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	/** A bearer token with no role: what Spring Security 7 builds when the roles claim is empty. */
	private static RequestPostProcessor withoutRole() {
		return jwt().authorities(new SimpleGrantedAuthority("FACTOR_BEARER"), new SimpleGrantedAuthority("SCOPE_openid"));
	}
}
