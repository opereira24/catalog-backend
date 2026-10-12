package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import pt.diamondcars.catalogbackend.domain.client.Client;
import pt.diamondcars.catalogbackend.domain.client.ClientRepository;
import pt.diamondcars.catalogbackend.domain.partner.Partner;
import pt.diamondcars.catalogbackend.domain.partner.PartnerRepository;
import pt.diamondcars.catalogbackend.domain.transaction.Transaction;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionRepository;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionType;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import pt.diamondcars.catalogbackend.web.dto.TransactionRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end tests of {@link TransactionController}, {@code TransactionService} and {@link
 * ApiExceptionHandler} through the real filter chain (TASK-005, AC D.1), with {@link MockMvc} against
 * the shared PostgreSQL container. Same context configuration as {@code ClientControllerTest} (no
 * {@code @MockitoBean}, {@code @Import} or extra properties), so the cached context is reused.
 *
 * <p>The first 13 tests are ported from {@code dcbo-backend}'s {@code TransactionControllerTest}
 * with the same method names; only the package and the {@code /api/backoffice} prefix changed (plus
 * the 403 message and stricter assertions where the original only checked the status). The 6 tests
 * of that class that go through car endpoints belong to TASK-010. The tests after them are new (AC
 * D.1 a-m).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class TransactionControllerTest extends AbstractPostgresIntegrationTest {

	private static final String PATH = "/api/backoffice/transactions";
	private static final String USER_ROLE = "ROLE_USER";
	private static final String ADMIN_ROLE = "ROLE_ADMIN";

	/** The 12 keys of AC B.2. A literal, so a component added or renamed by accident fails here. */
	private static final List<String> TRANSACTION_KEYS = List.of(
			"id", "tipo", "valor", "descricao", "categoria", "data", "carroId", "clienteId", "partnerId",
			"systemGenerated", "createdAt", "updatedAt");

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private TransactionRepository transactionRepository;
	@Autowired private CarRepository carRepository;
	@Autowired private ClientRepository clientRepository;
	@Autowired private PartnerRepository partnerRepository;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private EntityManagerFactory entityManagerFactory;

	/**
	 * Clears every row this class writes (the container is shared by the whole JVM, with no per-test
	 * rollback). Transactions first: they reference cars, clients and partners.
	 */
	@BeforeEach
	void cleanDatabase() {
		transactionRepository.deleteAllInBatch();
		carRepository.deleteAllInBatch();
		clientRepository.deleteAllInBatch();
		partnerRepository.deleteAllInBatch();
	}

	private static TransactionRequest aRequest() {
		return new TransactionRequest(
				"despesa", new BigDecimal("150.00"), "Manutencao geral", "manutencao", "2026-02-10", null);
	}

	private static TransactionRequest withValor(BigDecimal valor) {
		TransactionRequest valid = aRequest();
		return new TransactionRequest(valid.tipo(), valor, valid.descricao(), valid.categoria(), valid.data(), null);
	}

	private static TransactionRequest withData(String data) {
		TransactionRequest valid = aRequest();
		return new TransactionRequest(valid.tipo(), valid.valor(), valid.descricao(), valid.categoria(), data, null);
	}

	private static Car.CarBuilder aPersistedCar() {
		return Car.builder()
				.marca("Audi")
				.modelo("A4")
				.ano(2019)
				.preco(new BigDecimal("22000.00"))
				.km(80000)
				.cor("Branco")
				.combustivel("Gasolina")
				.transmissao("Manual")
				.origem("stand");
	}

	private Transaction saved(TransactionType tipo, String valor, LocalDate data) {
		return transactionRepository.saveAndFlush(
				Transaction.builder().tipo(tipo).valor(new BigDecimal(valor)).data(data).build());
	}

	// ---------------------------------------------------------------------------------------------
	// Ported from dcbo-backend (AC D.1, same method names)
	// ---------------------------------------------------------------------------------------------

	/**
	 * {@code valor = 0} responds 400 naming {@code valor}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsATransactionWithAValorOfZero() throws Exception {
		mockMvc
				.perform(postJson(withValor(BigDecimal.ZERO)).with(user()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", startsWith("valor: ")));

		assertThat(transactionRepository.count()).isZero();
	}

	/**
	 * Creates a transaction (201 with {@code Location}) and reads it back by id (200).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void createsATransactionAndReadsItBackById() throws Exception {
		String location = mockMvc
				.perform(postJson(aRequest()).with(user()))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", startsWith(PATH + "/")))
				.andExpect(jsonPath("$.tipo").value("despesa"))
				.andExpect(jsonPath("$.systemGenerated").value(false))
				.andReturn()
				.getResponse()
				.getHeader("Location");

		mockMvc
				.perform(get(location).with(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.descricao").value("Manutencao geral"))
				.andExpect(jsonPath("$.categoria").value("manutencao"))
				.andExpect(jsonPath("$.data").value("2026-02-10"));
	}

	/**
	 * {@code GET /{id}} of an id that does not exist responds 404 with the message of AC A.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void returns404ForAnUnknownTransactionId() throws Exception {
		UUID unknown = UUID.randomUUID();

		mockMvc
				.perform(get(PATH + "/{id}", unknown).with(user()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Transacao nao encontrada: " + unknown));
	}

	/**
	 * An unknown {@code carroId} on {@code POST} responds 400 (not 404, AC B.4) and stores nothing.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsATransactionWithAnUnknownCarroIdWith400() throws Exception {
		UUID unknownCar = UUID.randomUUID();
		TransactionRequest valid = aRequest();
		TransactionRequest withUnknownCar = new TransactionRequest(
				valid.tipo(), valid.valor(), valid.descricao(), valid.categoria(), valid.data(), unknownCar);

		mockMvc
				.perform(postJson(withUnknownCar).with(user()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("carroId: carro nao encontrado: " + unknownCar));

		assertThat(transactionRepository.count()).isZero();
	}

	/**
	 * A real {@code carroId} is accepted (201) and echoed back.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void acceptsATransactionWithARealCarroId() throws Exception {
		Car car = carRepository.saveAndFlush(aPersistedCar().build());
		TransactionRequest valid = aRequest();
		TransactionRequest withCar = new TransactionRequest(
				valid.tipo(), valid.valor(), valid.descricao(), valid.categoria(), valid.data(), car.getId());

		mockMvc
				.perform(postJson(withCar).with(user()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.carroId").value(car.getId().toString()));
	}

	/**
	 * Updates the business fields of a transaction.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void updatesATransaction() throws Exception {
		Transaction existing = saved(TransactionType.DESPESA, "50.00", LocalDate.of(2026, 1, 1));
		TransactionRequest update =
				new TransactionRequest("receita", new BigDecimal("999.99"), "Atualizada", "servicos", "2026-01-15", null);

		mockMvc
				.perform(put(PATH + "/{id}", existing.getId())
						.with(user())
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(update)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tipo").value("receita"))
				.andExpect(jsonPath("$.valor").value(999.99))
				.andExpect(jsonPath("$.descricao").value("Atualizada"));

		Transaction reloaded = transactionRepository.findById(existing.getId()).orElseThrow();
		assertThat(reloaded.getTipo()).isEqualTo(TransactionType.RECEITA);
		assertThat(reloaded.getData()).isEqualTo(LocalDate.of(2026, 1, 15));
	}

	/**
	 * Deletes a transaction (204).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void deletesATransaction() throws Exception {
		Transaction existing = saved(TransactionType.DESPESA, "50.00", LocalDate.of(2026, 1, 1));

		mockMvc
				.perform(delete(PATH + "/{id}", existing.getId()).with(user()))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		assertThat(transactionRepository.existsById(existing.getId())).isFalse();
	}

	/**
	 * {@code ?from=&to=} excludes transactions dated outside the interval (before, inside, after).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void filtersTheListingByDateRange() throws Exception {
		saved(TransactionType.DESPESA, "10.00", LocalDate.of(2026, 1, 1));
		Transaction insideRange = saved(TransactionType.DESPESA, "20.00", LocalDate.of(2026, 2, 15));
		saved(TransactionType.DESPESA, "30.00", LocalDate.of(2026, 3, 31));

		mockMvc
				.perform(get(PATH + "?from=2026-02-01&to=2026-02-28").with(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(insideRange.getId().toString()));
	}

	/**
	 * AC A.2: {@code from} and {@code to} are inclusive on both sides (review r1, I-3). One
	 * transaction on each bound and one on the day just outside each: a monthly filter keeps its
	 * first and last day, and an exclusive {@code >}/{@code <} in {@code TransactionSpecifications}
	 * would drop one of them.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void theDateRangeFilterIncludesBothBounds() throws Exception {
		saved(TransactionType.DESPESA, "10.00", LocalDate.of(2026, 1, 31));
		Transaction firstDay = saved(TransactionType.DESPESA, "20.00", LocalDate.of(2026, 2, 1));
		Transaction lastDay = saved(TransactionType.DESPESA, "30.00", LocalDate.of(2026, 2, 28));
		saved(TransactionType.DESPESA, "40.00", LocalDate.of(2026, 3, 1));

		JsonNode content = getJson(get(PATH).param("from", "2026-02-01").param("to", "2026-02-28")).get("content");

		List<String> ids = new ArrayList<>();
		content.forEach(transaction -> ids.add(transaction.get("id").asString()));
		assertThat(ids).containsExactly(lastDay.getId().toString(), firstDay.getId().toString());
	}

	/**
	 * {@code ?tipo=compra} filters by the lower-case value, through {@link
	 * TransactionTypeQueryConverter}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void filtersTheListingByTipo() throws Exception {
		Transaction compra = saved(TransactionType.COMPRA, "10.00", LocalDate.of(2026, 1, 1));
		saved(TransactionType.RECEITA, "20.00", LocalDate.of(2026, 1, 2));
		saved(TransactionType.DESPESA, "30.00", LocalDate.of(2026, 1, 3));

		mockMvc
				.perform(get(PATH + "?tipo=compra").with(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(compra.getId().toString()));
	}

	/**
	 * Listing without a token responds 401.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listingTransactionsWithoutATokenIsUnauthorized() throws Exception {
		mockMvc
				.perform(get(PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Autenticacao necessaria"));
	}

	/**
	 * {@code data = "2026-03-31T23:30:00Z"} stores {@code 2026-04-01}: Lisbon is already on summer
	 * time (UTC+1) on that date, one day ahead of the UTC instant.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void resolvesAFullIsoInstantToTheLisbonCalendarDate() throws Exception {
		String location = createAndGetLocation(withData("2026-03-31T23:30:00Z"));

		mockMvc
				.perform(get(location).with(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").value("2026-04-01"));
	}

	/**
	 * {@code data = "2026-03-31"} is stored as-is.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void resolvesAPlainCalendarDateAsIs() throws Exception {
		mockMvc
				.perform(postJson(withData("2026-03-31")).with(user()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data").value("2026-03-31"));
	}

	/**
	 * A {@code data} in neither format responds 400 with the message of AC B.4.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsAMalformedData() throws Exception {
		mockMvc
				.perform(postJson(withData("not-a-date")).with(user()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(
						"data: valor invalido, esperado YYYY-MM-DD ou instante ISO-8601 (ex. 2026-03-31T23:30:00Z): not-a-date"));

		assertThat(transactionRepository.count()).isZero();
	}

	// ---------------------------------------------------------------------------------------------
	// New in TASK-005 (AC D.1 a-m)
	// ---------------------------------------------------------------------------------------------

	/**
	 * AC D.1.a: without a token, {@code GET /{id}}, {@code POST}, {@code PUT /{id}} and {@code DELETE
	 * /{id}} are 401; a token without a role is 403 on listing and on a valid {@code POST} (which
	 * stores nothing); {@code ADMIN} lists (200).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void everyEndpointNeedsATokenAndABackOfficeRole() throws Exception {
		Transaction existing = saved(TransactionType.DESPESA, "50.00", LocalDate.of(2026, 1, 1));
		String body = objectMapper.writeValueAsString(aRequest());
		List<MockHttpServletRequestBuilder> withoutToken = List.of(
				get(PATH + "/{id}", existing.getId()),
				post(PATH).contentType(MediaType.APPLICATION_JSON).content(body),
				put(PATH + "/{id}", existing.getId()).contentType(MediaType.APPLICATION_JSON).content(body),
				delete(PATH + "/{id}", existing.getId()));
		for (MockHttpServletRequestBuilder request : withoutToken) {
			mockMvc
					.perform(request)
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.message").value("Autenticacao necessaria"));
		}

		mockMvc
				.perform(get(PATH).with(withoutRole()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Autenticado, mas sem a role exigida para esta operacao"))
				.andExpect(jsonPath("$.content").doesNotExist());
		mockMvc
				.perform(postJson(aRequest()).with(withoutRole()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Autenticado, mas sem a role exigida para esta operacao"));
		mockMvc
				.perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1));

		Transaction reloaded = transactionRepository.findById(existing.getId()).orElseThrow();
		assertThat(transactionRepository.count()).isEqualTo(1);
		assertThat(reloaded.getValor()).isEqualByComparingTo("50.00");
	}

	/**
	 * AC D.1.b: a body carrying {@code systemGenerated: true} and the {@code clienteId}/{@code
	 * partnerId} of real entities stores a manual transaction without client or partner. Literal JSON,
	 * because {@link TransactionRequest} cannot even express those keys.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void theBodyCannotMarkATransactionAsSystemGeneratedNorLinkAClientOrPartner() throws Exception {
		Client client = clientRepository.saveAndFlush(Client.builder().name("Ana").phone("912345678").build());
		Partner partner = partnerRepository.saveAndFlush(Partner.builder().name("Auto Silva").build());
		String body = """
				{"tipo":"venda","valor":100.00,"descricao":"Venda","categoria":null,"data":"2026-03-01",
				 "systemGenerated":true,"clienteId":"%s","partnerId":"%s"}"""
				.formatted(client.getId(), partner.getId());

		String location = mockMvc
				.perform(post(PATH).with(user()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.systemGenerated").value(false))
				.andExpect(jsonPath("$.clienteId").value(nullValue()))
				.andExpect(jsonPath("$.partnerId").value(nullValue()))
				.andReturn()
				.getResponse()
				.getHeader("Location");

		JsonNode reread = getJson(get(location));
		assertThat(reread.get("systemGenerated").asBoolean()).isFalse();
		assertThat(reread.get("clienteId").isNull()).isTrue();
		assertThat(reread.get("partnerId").isNull()).isTrue();
		Transaction stored = transactionRepository.findAll().getFirst();
		assertThat(stored.isSystemGenerated()).isFalse();
		assertThat(stored.getClient()).isNull();
		assertThat(stored.getPartner()).isNull();
	}

	/**
	 * AC D.1.c: {@code PUT} on a system-generated transaction with car, client and partner, with the
	 * {@code carroId} of another car in the body, changes the 5 business fields and nothing else.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void updatingASystemGeneratedTransactionChangesOnlyTheFiveBusinessFields() throws Exception {
		Client client = clientRepository.saveAndFlush(Client.builder().name("Ana").phone("912345678").build());
		Partner partner = partnerRepository.saveAndFlush(Partner.builder().name("Auto Silva").build());
		Car soldCar = carRepository.saveAndFlush(aPersistedCar().client(client).build());
		Car otherCar = carRepository.saveAndFlush(aPersistedCar().modelo("A6").build());
		Transaction sale = transactionRepository.saveAndFlush(Transaction.builder()
				.tipo(TransactionType.VENDA)
				.valor(new BigDecimal("21000.00"))
				.descricao("Venda Audi A4")
				.categoria("venda_veiculo")
				.data(LocalDate.of(2026, 3, 1))
				.car(soldCar)
				.client(client)
				.partner(partner)
				.systemGenerated(true)
				.build());
		TransactionRequest edit = new TransactionRequest(
				"receita", new BigDecimal("20500.50"), "Venda corrigida", "ajuste", "2026-03-02", otherCar.getId());

		JsonNode response = objectMapper.readTree(mockMvc
				.perform(put(PATH + "/{id}", sale.getId())
						.with(user())
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(edit)))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString());
		JsonNode reread = getJson(get(PATH + "/{id}", sale.getId()));

		for (JsonNode node : List.of(response, reread)) {
			assertThat(node.get("tipo").asString()).isEqualTo("receita");
			assertThat(node.get("valor").decimalValue()).isEqualByComparingTo("20500.50");
			assertThat(node.get("descricao").asString()).isEqualTo("Venda corrigida");
			assertThat(node.get("categoria").asString()).isEqualTo("ajuste");
			assertThat(node.get("data").asString()).isEqualTo("2026-03-02");
			assertThat(node.get("carroId").asString()).isEqualTo(soldCar.getId().toString());
			assertThat(node.get("clienteId").asString()).isEqualTo(client.getId().toString());
			assertThat(node.get("partnerId").asString()).isEqualTo(partner.getId().toString());
			assertThat(node.get("systemGenerated").asBoolean()).isTrue();
		}
	}

	/**
	 * AC D.1.d: {@code valor} with 3 decimal places is a 400 (the column would round it silently and
	 * the 201 would lie); {@code 10.5} is stored and read back as {@code 10.50}; the ceiling is
	 * {@code 10000000.00} inclusive.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void valorHasAtMostTwoDecimalPlacesAndAtMostTenMillion() throws Exception {
		for (String refused : List.of("10.005", "10000000.01")) {
			mockMvc
					.perform(postJson(withValor(new BigDecimal(refused))).with(user()))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message", startsWith("valor: ")));
		}
		assertThat(transactionRepository.count()).isZero();

		String location = createAndGetLocation(withValor(new BigDecimal("10.5")));
		mockMvc
				.perform(get(location).with(user()))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("\"valor\":10.50")));
		mockMvc
				.perform(postJson(withValor(new BigDecimal("10000000.00"))).with(user()))
				.andExpect(status().isCreated());
	}

	/**
	 * AC D.1.e: {@code data} outside {@code [0001-01-01, 9999-12-31]} (both parsed by {@code
	 * LocalDate.parse}) or longer than 64 characters is a 400 naming {@code data}; the two limits
	 * themselves are accepted and read back as-is.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void dataIsBetweenYearOneAndYear9999AndAtMost64Characters() throws Exception {
		String overTheCap = "2026-03-31" + "x".repeat(55);
		for (String refused : List.of("+10000-01-01", "0000-12-31", overTheCap)) {
			mockMvc
					.perform(postJson(withData(refused)).with(user()))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message", startsWith("data: ")));
		}
		// The 64-character cap is checked by @Size, before resolveData, whose message echoes the value:
		// one character over the cap is refused without being repeated back (review r1, S-1).
		mockMvc
				.perform(postJson(withData(overTheCap)).with(user()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message", not(containsString(overTheCap))));
		assertThat(transactionRepository.count()).isZero();

		for (String limit : List.of("0001-01-01", "9999-12-31")) {
			String location = createAndGetLocation(withData(limit));
			mockMvc.perform(get(location).with(user())).andExpect(jsonPath("$.data").value(limit));
		}
	}

	/**
	 * AC D.1.f: {@code 2026-03-31T22:30:00Z} is 23:30 in Lisbon, still {@code 2026-03-31}. With
	 * {@link #resolvesAFullIsoInstantToTheLisbonCalendarDate()} it rules out both "truncate in UTC"
	 * and "always add a day".
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void resolvesAnInstantStillOnTheSameLisbonDayToThatDay() throws Exception {
		String location = createAndGetLocation(withData("2026-03-31T22:30:00Z"));

		mockMvc
				.perform(get(location).with(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").value("2026-03-31"));
	}

	/**
	 * AC D.1.g: {@code ?tipo=} only takes the lower-case values; the Java constant name and anything
	 * else are a 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void theTipoFilterOnlyAcceptsTheLowerCaseValues() throws Exception {
		for (String tipo : List.of("VENDA", "nonsense")) {
			mockMvc
					.perform(get(PATH).param("tipo", tipo).with(user()))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value("Parametro 'tipo' com valor invalido"));
		}
	}

	/**
	 * An empty or blank {@code ?tipo=} does not filter (review r1, I-1): it is what the "all types"
	 * option of a form sends, and the empty {@code ?carroId=}/{@code ?from=} of the same route do not
	 * filter either. Without the blank check in {@link TransactionTypeQueryConverter} both were a 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void anEmptyTipoFilterListsEveryType() throws Exception {
		saved(TransactionType.COMPRA, "10.00", LocalDate.of(2026, 1, 1));
		saved(TransactionType.RECEITA, "20.00", LocalDate.of(2026, 1, 2));

		for (String tipo : List.of("", " ")) {
			assertThat(getJson(get(PATH).param("tipo", tipo)).get("content")).as("tipo=[%s]", tipo).hasSize(2);
		}
	}

	/**
	 * AC D.1.h: {@code sort} through an association is a 400 before any query; a scalar property
	 * sorts.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void sortOnlyAcceptsScalarPropertiesOfTheTransaction() throws Exception {
		saved(TransactionType.DESPESA, "30.00", LocalDate.of(2026, 1, 1));
		saved(TransactionType.DESPESA, "10.00", LocalDate.of(2026, 1, 2));
		saved(TransactionType.DESPESA, "20.00", LocalDate.of(2026, 1, 3));

		mockMvc
				.perform(get(PATH).param("sort", "car.marca").with(user()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Campo de ordenacao desconhecido: car.marca"));

		JsonNode content = getJson(get(PATH).param("sort", "valor,asc")).get("content");
		List<BigDecimal> valores = new ArrayList<>();
		content.forEach(transaction -> valores.add(transaction.get("valor").decimalValue()));
		assertThat(valores).usingElementComparator(BigDecimal::compareTo).containsExactly(
				new BigDecimal("10.00"), new BigDecimal("20.00"), new BigDecimal("30.00"));
	}

	/**
	 * AC D.1.i: default order is {@code data}, then {@code createdAt}, then {@code id}, all
	 * descending. Three transactions on the same day (two with the same {@code createdAt}) and one
	 * later: the later one first, then the other three in exactly that order across pages of one.
	 * Inserted by SQL in ascending {@code createdAt}, so a missing tie-breaker shows up as the heap
	 * order (mutation 10 of AC D.7). The earliest of the day has the highest {@code id}, so {@code
	 * data, id} without {@code createdAt} puts it first of the day and fails too (review r1, I-2: with
	 * {@code id} growing with {@code createdAt} both orders were the same).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void defaultOrderIsDataThenCreatedAtThenIdDescendingAndPagesAreStable() throws Exception {
		Timestamp earlier = Timestamp.from(Instant.parse("2026-03-10T09:00:00Z"));
		Timestamp later = Timestamp.from(Instant.parse("2026-03-10T18:00:00Z"));
		String earlierSameDay = "00000000-0000-0000-0000-000000000009";
		String laterSameDayLowId = "00000000-0000-0000-0000-000000000002";
		String laterSameDayHighId = "00000000-0000-0000-0000-000000000003";
		String nextDay = "00000000-0000-0000-0000-000000000004";
		insertTransaction(earlierSameDay, LocalDate.of(2026, 3, 10), earlier);
		insertTransaction(laterSameDayLowId, LocalDate.of(2026, 3, 10), later);
		insertTransaction(laterSameDayHighId, LocalDate.of(2026, 3, 10), later);
		insertTransaction(nextDay, LocalDate.of(2026, 3, 11), earlier);
		jdbcTemplate.execute("ANALYZE transactions");

		List<String> paged = new ArrayList<>();
		for (int page = 0; page < 4; page++) {
			JsonNode listing = getJson(get(PATH).param("size", "1").param("page", String.valueOf(page)));
			assertThat(listing.get("page").get("totalElements").asLong()).isEqualTo(4);
			listing.get("content").forEach(transaction -> paged.add(transaction.get("id").asString()));
		}

		assertThat(paged).containsExactly(nextDay, laterSameDayHighId, laterSameDayLowId, earlierSameDay);
	}

	/**
	 * AC D.1.j: {@code ?carroId=} returns only that car's transactions; a car that does not exist
	 * gives an empty page, not an error.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void filtersTheListingByCarroId() throws Exception {
		Car car = carRepository.saveAndFlush(aPersistedCar().build());
		Car otherCar = carRepository.saveAndFlush(aPersistedCar().modelo("A6").build());
		Transaction ofCar = transactionRepository.saveAndFlush(Transaction.builder()
				.tipo(TransactionType.COMPRA).valor(new BigDecimal("18000.00")).data(LocalDate.of(2026, 1, 1)).car(car).build());
		transactionRepository.saveAndFlush(Transaction.builder()
				.tipo(TransactionType.COMPRA).valor(new BigDecimal("19000.00")).data(LocalDate.of(2026, 1, 1)).car(otherCar).build());
		saved(TransactionType.DESPESA, "10.00", LocalDate.of(2026, 1, 1));

		mockMvc
				.perform(get(PATH).param("carroId", car.getId().toString()).with(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(ofCar.getId().toString()));
		mockMvc
				.perform(get(PATH).param("carroId", UUID.randomUUID().toString()).with(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(0))
				.andExpect(jsonPath("$.page.totalElements").value(0));
	}

	/**
	 * AC D.1.k: {@code GET /{id}} has exactly the 12 keys of AC B.2, {@code data} as text.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void exposesExactlyTheTwelveKeysWithDataAsText() throws Exception {
		Transaction existing = saved(TransactionType.RECEITA, "75.00", LocalDate.of(2026, 3, 31));

		JsonNode transaction = getJson(get(PATH + "/{id}", existing.getId()));

		assertThat(new ArrayList<>(transaction.propertyNames())).containsExactlyInAnyOrderElementsOf(TRANSACTION_KEYS);
		assertThat(transaction.get("data").isString()).isTrue();
		assertThat(transaction.get("data").asString()).isEqualTo("2026-03-31");
		assertThat(transaction.get("tipo").asString()).isEqualTo("receita");
	}

	/**
	 * AC D.1.l: {@code PUT} and {@code DELETE} of an unknown id are 404; a non-UUID id is 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void unknownIdsAre404AndANonUuidIdIs400() throws Exception {
		UUID unknown = UUID.randomUUID();

		mockMvc
				.perform(put(PATH + "/{id}", unknown)
						.with(user())
						.contentType(MediaType.APPLICATION_JSON)
						.content(objectMapper.writeValueAsString(aRequest())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Transacao nao encontrada: " + unknown));
		mockMvc
				.perform(delete(PATH + "/{id}", unknown).with(user()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Transacao nao encontrada: " + unknown));
		mockMvc
				.perform(get(PATH + "/nao-e-uuid").with(user()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Parametro 'id' com valor invalido"));
	}

	/**
	 * AC D.1.m: listing 100 transactions, each with car, client and partner, runs at most 3
	 * statements (page, {@code count}, the {@code ActiveUserInterceptor} lookup): the DTO only reads
	 * {@code getId()} of the {@code LAZY} proxies.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listingOneHundredTransactionsRunsAtMostThreeStatements() throws Exception {
		Client client = clientRepository.saveAndFlush(Client.builder().name("Ana").phone("912345678").build());
		Partner partner = partnerRepository.saveAndFlush(Partner.builder().name("Auto Silva").build());
		Car car = carRepository.saveAndFlush(aPersistedCar().client(client).partner(partner).build());
		List<Transaction> transactions = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			transactions.add(Transaction.builder()
					.tipo(TransactionType.VENDA)
					.valor(new BigDecimal("100.00"))
					.data(LocalDate.of(2026, 1, 1).plusDays(i))
					.car(car)
					.client(client)
					.partner(partner)
					.build());
		}
		transactionRepository.saveAllAndFlush(transactions);

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();
		JsonNode listing;
		long statements;
		try {
			listing = getJson(get(PATH).param("size", "100"));
			statements = statistics.getPrepareStatementCount();
		} finally {
			statistics.setStatisticsEnabled(false);
		}

		assertThat(listing.get("content")).hasSize(100);
		for (JsonNode transaction : listing.get("content")) {
			assertThat(transaction.get("carroId").asString()).isEqualTo(car.getId().toString());
			assertThat(transaction.get("clienteId").asString()).isEqualTo(client.getId().toString());
			assertThat(transaction.get("partnerId").asString()).isEqualTo(partner.getId().toString());
		}
		assertThat(statements).isPositive().isLessThanOrEqualTo(3);
	}

	/**
	 * Date filters outside the range PostgreSQL's {@code DATE} accepts (both parsed by {@code
	 * ?from=}/{@code ?to=}) are 200 with the right result, never the 409 that PostgreSQL's {@code date out of range} becomes: {@code
	 * from} far in the past lists everything, {@code to} far in the past and {@code from} far in the
	 * future list nothing (ASSUNÇÃO of the developer, TASK-005 Notas: AC C.2 only clamps {@code from}
	 * down and {@code to} up).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void dateFiltersBeyondTheDatabaseRangeAnswerWithoutError() throws Exception {
		saved(TransactionType.DESPESA, "10.00", LocalDate.of(2026, 1, 1));
		// One row on each limit of the accepted range: an empty bound clamped to MAX_DATA/MIN_DATA
		// instead of one day past it would return them (review r1, S-2).
		saved(TransactionType.DESPESA, "20.00", TransactionRequest.MIN_DATA);
		saved(TransactionType.DESPESA, "30.00", TransactionRequest.MAX_DATA);

		assertThat(getJson(get(PATH).param("from", "-5000-01-01")).get("content")).hasSize(3);
		assertThat(getJson(get(PATH).param("to", "+999999999-12-31")).get("content")).hasSize(3);
		assertThat(getJson(get(PATH).param("to", "-5000-01-01")).get("content")).isEmpty();
		assertThat(getJson(get(PATH).param("from", "+999999999-12-31")).get("content")).isEmpty();
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------

	private static RequestPostProcessor user() {
		return jwt().authorities(new SimpleGrantedAuthority(USER_ROLE));
	}

	/** A bearer token with no role: what Spring Security 7 builds when the roles claim is empty. */
	private static RequestPostProcessor withoutRole() {
		return jwt().authorities(new SimpleGrantedAuthority("FACTOR_BEARER"), new SimpleGrantedAuthority("SCOPE_openid"));
	}

	private MockHttpServletRequestBuilder postJson(TransactionRequest body) {
		return post(PATH).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	private String createAndGetLocation(TransactionRequest body) throws Exception {
		return mockMvc
				.perform(postJson(body).with(user()))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", notNullValue()))
				.andReturn()
				.getResponse()
				.getHeader("Location");
	}

	private JsonNode getJson(MockHttpServletRequestBuilder request) throws Exception {
		String response = mockMvc
				.perform(request.with(user()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		return objectMapper.readTree(response);
	}

	private void insertTransaction(String id, LocalDate data, Timestamp createdAt) {
		jdbcTemplate.update(
				"INSERT INTO transactions (id, tipo, valor, descricao, data, created_at, updated_at)"
						+ " VALUES (?::uuid, 'despesa', 10.00, 'Despesa', ?, ?, ?)",
				id, Date.valueOf(data), createdAt, createdAt);
	}
}
