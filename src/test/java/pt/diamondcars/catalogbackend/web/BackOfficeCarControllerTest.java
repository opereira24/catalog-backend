package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import pt.diamondcars.catalogbackend.domain.car.CarImage;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.client.Client;
import pt.diamondcars.catalogbackend.domain.client.ClientRepository;
import pt.diamondcars.catalogbackend.domain.partner.Partner;
import pt.diamondcars.catalogbackend.domain.partner.PartnerRepository;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionRepository;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import pt.diamondcars.catalogbackend.web.dto.CarRequest;
import pt.diamondcars.catalogbackend.web.dto.HighlightRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * End-to-end tests of {@link BackOfficeCarController}, {@code BackOfficeCarService} and {@link
 * ApiExceptionHandler} through the real filter chain (TASK-003), with {@link MockMvc} against the
 * shared PostgreSQL container. The {@code jwt()} post-processor injects an authenticated principal
 * with the given authorities; the token decoding path is proven by the TASK-002 tests.
 *
 * <p>The first 23 tests are ported from {@code dcbo-backend}'s {@code CarControllerTest} with the
 * same method names (the TASK-003 AC I.1 table and its script check them); only the package, the
 * path prefix and the {@code observacoes} argument of {@link CarRequest} changed. The tests after
 * them are new (AC I.4).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class BackOfficeCarControllerTest extends AbstractPostgresIntegrationTest {

	private static final String ADMIN_ROLE = "ROLE_ADMIN";
	private static final String USER_ROLE = "ROLE_USER";

	/**
	 * The exact keys of the back-office car (AC C.5). A literal, not derived from the record, so a
	 * component added or renamed by accident fails here.
	 */
	private static final List<String> BACK_OFFICE_CAR_KEYS = List.of(
			"id", "marca", "modelo", "ano", "preco", "km", "cor", "combustivel", "transmissao", "origem",
			"descricao", "observacoes", "precoCompra", "dataCompra", "isConsignacao", "partnerId",
			"commissionValue", "garantiaMeses", "destaque", "vendido", "reservado", "dataVenda",
			"precoVenda", "clienteId", "images", "imageThumbnails", "createdAt", "updatedAt");

	/** The 18 public keys frozen by TASK-001 (D.7), repeated here on purpose (AC G.2). */
	private static final List<String> PUBLIC_CAR_KEYS = List.of(
			"id", "_id", "marca", "modelo", "ano", "preco", "km", "cor", "combustivel", "descricao",
			"observacoes", "garantiaMeses", "vendido", "reservado", "destaque", "images", "createdAt",
			"updatedAt");

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private CarRepository carRepository;
	@Autowired private ClientRepository clientRepository;
	@Autowired private PartnerRepository partnerRepository;
	@Autowired private TransactionRepository transactionRepository;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private EntityManagerFactory entityManagerFactory;

	/**
	 * Clears every row these tests write or count, so no test sees another's cars (the container is
	 * shared by the whole JVM, with no per-test rollback). Transactions first: they may reference
	 * partners and clients.
	 */
	@BeforeEach
	void cleanDatabase() {
		transactionRepository.deleteAllInBatch();
		carRepository.deleteAllInBatch();
		clientRepository.deleteAllInBatch();
		partnerRepository.deleteAllInBatch();
	}

	private static CarRequest validCarRequest() {
		return new CarRequest(
				"BMW",
				"320d",
				2020,
				new BigDecimal("25000.00"),
				50000,
				"Preto",
				"Diesel",
				"Automatica",
				"stand",
				"Carro em otimo estado",
				null,
				new BigDecimal("20000.00"),
				null,
				false,
				null,
				null,
				12,
				false,
				List.of("https://img/1.jpg", "https://img/2.jpg"),
				List.of("https://img/1-thumb.jpg", "https://img/2-thumb.jpg"));
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

	// ---------------------------------------------------------------------------------------------
	// Ported from dcbo-backend's CarControllerTest (AC I.1, rows marked 003)
	// ---------------------------------------------------------------------------------------------

	/**
	 * C01: a valid create returns 201 with {@code Location} under the back-office prefix, and the
	 * car can be read back from it.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void createsACarAndReadsItBackById() throws Exception {
		String location =
				mockMvc
						.perform(
								post("/api/backoffice/cars")
										.with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE)))
										.contentType(MediaType.APPLICATION_JSON)
										.content(objectMapper.writeValueAsString(validCarRequest())))
						.andExpect(status().isCreated())
						.andExpect(header().string("Location", notNullValue()))
						.andExpect(jsonPath("$.marca").value("BMW"))
						.andExpect(jsonPath("$.images[0]").value("https://img/1.jpg"))
						.andReturn()
						.getResponse()
						.getHeader("Location");

		assertThat(location).matches("/api/backoffice/cars/[0-9a-f-]{36}");
		mockMvc
				.perform(get(location).with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.modelo").value("320d"));
	}

	/**
	 * C02: a price below the 100 floor is a 400 that names the field.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsACarWithAPriceBelowTheFloor() throws Exception {
		CarRequest valid = validCarRequest();
		CarRequest invalid =
				new CarRequest(
						valid.marca(),
						valid.modelo(),
						valid.ano(),
						new BigDecimal("50"),
						valid.km(),
						valid.cor(),
						valid.combustivel(),
						valid.transmissao(),
						valid.origem(),
						valid.descricao(),
						valid.observacoes(),
						valid.precoCompra(),
						valid.dataCompra(),
						valid.isConsignacao(),
						valid.partnerId(),
						valid.commissionValue(),
						valid.garantiaMeses(),
						valid.destaque(),
						valid.images(),
						valid.imageThumbnails());

		mockMvc
				.perform(
						post("/api/backoffice/cars")
								.with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(invalid)))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("preco")));
	}

	/**
	 * C03: an unknown car id is a 404.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void returns404ForAnUnknownCarId() throws Exception {
		mockMvc
				.perform(
						get("/api/backoffice/cars/{id}", UUID.randomUUID())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isNotFound());
	}

	/**
	 * C05: reserving and then releasing a car flips {@code reservado}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void reservesAndReleasesACar() throws Exception {
		Car car = carRepository.saveAndFlush(aPersistedCar().build());

		mockMvc
				.perform(
						post("/api/backoffice/cars/{id}/reserve", car.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reservado").value(true));

		mockMvc
				.perform(
						post("/api/backoffice/cars/{id}/release-reservation", car.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reservado").value(false));
	}

	/**
	 * C06: with 8 cars featured, featuring a 9th is a 409.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsFeaturingAWinthCarWhenTheLimitOfEightIsAlreadyReached() throws Exception {
		for (int i = 0; i < 8; i++) {
			carRepository.saveAndFlush(aPersistedCar().modelo("Featured " + i).destaque(true).build());
		}
		Car ninthCar = carRepository.saveAndFlush(aPersistedCar().modelo("Ninth").build());

		mockMvc
				.perform(
						patch("/api/backoffice/cars/{id}/highlight", ninthCar.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(new HighlightRequest(true))))
				.andExpect(status().isConflict());
	}

	/**
	 * C07: with 7 featured, featuring the 8th succeeds; together with C06 this fixes the limit at
	 * exactly 8.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void allowsFeaturingExactlyTheEighthCar() throws Exception {
		for (int i = 0; i < 7; i++) {
			carRepository.saveAndFlush(aPersistedCar().modelo("Featured " + i).destaque(true).build());
		}
		Car eighthCar = carRepository.saveAndFlush(aPersistedCar().modelo("Eighth").build());

		mockMvc
				.perform(
						patch("/api/backoffice/cars/{id}/highlight", eighthCar.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(new HighlightRequest(true))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.destaque").value(true));
	}

	/**
	 * C08: un-featuring a car frees a slot for another.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void unfeaturingACarFreesUpASlotForAnother() throws Exception {
		Car firstFeatured = null;
		for (int i = 0; i < 8; i++) {
			Car featured =
					carRepository.saveAndFlush(aPersistedCar().modelo("Featured " + i).destaque(true).build());
			if (i == 0) {
				firstFeatured = featured;
			}
		}
		Car waiting = carRepository.saveAndFlush(aPersistedCar().modelo("Waiting").build());

		mockMvc
				.perform(
						patch("/api/backoffice/cars/{id}/highlight", firstFeatured.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(new HighlightRequest(false))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.destaque").value(false));

		mockMvc
				.perform(
						patch("/api/backoffice/cars/{id}/highlight", waiting.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(new HighlightRequest(true))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.destaque").value(true));
	}

	/**
	 * C09: the back-office listing without a token is a 401.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listingCarsWithoutATokenIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/backoffice/cars")).andExpect(status().isUnauthorized());
	}

	/**
	 * C10: the listing is most recently created first by default.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listsCarsMostRecentlyCreatedFirstByDefault() throws Exception {
		Car first = carRepository.saveAndFlush(aPersistedCar().modelo("First").build());
		Car second = carRepository.saveAndFlush(aPersistedCar().modelo("Second").build());
		Car third = carRepository.saveAndFlush(aPersistedCar().modelo("Third").build());

		mockMvc
				.perform(get("/api/backoffice/cars").with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(3))
				.andExpect(jsonPath("$.content[0].id").value(third.getId().toString()))
				.andExpect(jsonPath("$.content[1].id").value(second.getId().toString()))
				.andExpect(jsonPath("$.content[2].id").value(first.getId().toString()));
	}

	/**
	 * C11: {@code ?vendido=} filters by that exact value, {@code false} included ({@code vendido} is
	 * set by the builder here; selling is TASK-010).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void filtersTheListingByVendido() throws Exception {
		Car sold = carRepository.saveAndFlush(aPersistedCar().modelo("Sold").vendido(true).build());
		Car available = carRepository.saveAndFlush(aPersistedCar().modelo("Available").vendido(false).build());

		mockMvc
				.perform(
						get("/api/backoffice/cars?vendido=true")
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(sold.getId().toString()));

		mockMvc
				.perform(
						get("/api/backoffice/cars?vendido=false")
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(available.getId().toString()));
	}

	/**
	 * C12: two filters together are combined with AND.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void combinesTheVendidoAndDestaqueFiltersWithAnd() throws Exception {
		Car match =
				carRepository.saveAndFlush(
						aPersistedCar().modelo("Match").vendido(false).destaque(true).build());
		carRepository.saveAndFlush(
				aPersistedCar().modelo("SoldAndFeatured").vendido(true).destaque(true).build());
		carRepository.saveAndFlush(
				aPersistedCar().modelo("AvailableNotFeatured").vendido(false).destaque(false).build());

		mockMvc
				.perform(
						get("/api/backoffice/cars?vendido=false&destaque=true")
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(match.getId().toString()));
	}

	/**
	 * C18: an unknown {@code partnerId} is a 404, not a foreign key violation at flush.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void creatingACarWithAnUnknownPartnerIdIsRejectedWith404() throws Exception {
		CarRequest valid = validCarRequest();
		CarRequest withUnknownPartner =
				new CarRequest(
						valid.marca(),
						valid.modelo(),
						valid.ano(),
						valid.preco(),
						valid.km(),
						valid.cor(),
						valid.combustivel(),
						valid.transmissao(),
						valid.origem(),
						valid.descricao(),
						valid.observacoes(),
						valid.precoCompra(),
						valid.dataCompra(),
						true,
						UUID.randomUUID(),
						valid.commissionValue(),
						valid.garantiaMeses(),
						valid.destaque(),
						valid.images(),
						valid.imageThumbnails());

		mockMvc
				.perform(
						post("/api/backoffice/cars")
								.with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(withUnknownPartner)))
				.andExpect(status().isNotFound());
	}

	/**
	 * C19: a real {@code partnerId} is accepted and echoed back.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void creatingACarWithARealPartnerIncludesItsIdInTheResponse() throws Exception {
		Partner partner =
				partnerRepository.saveAndFlush(Partner.builder().name("Parceiro Teste").build());
		CarRequest valid = validCarRequest();
		CarRequest withPartner =
				new CarRequest(
						valid.marca(),
						valid.modelo(),
						valid.ano(),
						valid.preco(),
						valid.km(),
						valid.cor(),
						valid.combustivel(),
						valid.transmissao(),
						valid.origem(),
						valid.descricao(),
						valid.observacoes(),
						valid.precoCompra(),
						valid.dataCompra(),
						true,
						partner.getId(),
						valid.commissionValue(),
						valid.garantiaMeses(),
						valid.destaque(),
						valid.images(),
						valid.imageThumbnails());

		mockMvc
				.perform(
						post("/api/backoffice/cars")
								.with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(withPartner)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.partnerId").value(partner.getId().toString()));
	}

	/**
	 * C20: a model year far in the future is a 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsACarWithAModelYearTooFarInTheFuture() throws Exception {
		CarRequest valid = validCarRequest();
		CarRequest invalid =
				new CarRequest(
						valid.marca(),
						valid.modelo(),
						9999,
						valid.preco(),
						valid.km(),
						valid.cor(),
						valid.combustivel(),
						valid.transmissao(),
						valid.origem(),
						valid.descricao(),
						valid.observacoes(),
						valid.precoCompra(),
						valid.dataCompra(),
						valid.isConsignacao(),
						valid.partnerId(),
						valid.commissionValue(),
						valid.garantiaMeses(),
						valid.destaque(),
						valid.images(),
						valid.imageThumbnails());

		mockMvc
				.perform(
						post("/api/backoffice/cars")
								.with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(invalid)))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("ano")));
	}

	/**
	 * C21: a price above the 10 million business ceiling is a 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsACarWithAPriceAboveTheBusinessCeiling() throws Exception {
		CarRequest valid = validCarRequest();
		CarRequest invalid =
				new CarRequest(
						valid.marca(),
						valid.modelo(),
						valid.ano(),
						new BigDecimal("50000000"),
						valid.km(),
						valid.cor(),
						valid.combustivel(),
						valid.transmissao(),
						valid.origem(),
						valid.descricao(),
						valid.observacoes(),
						valid.precoCompra(),
						valid.dataCompra(),
						valid.isConsignacao(),
						valid.partnerId(),
						valid.commissionValue(),
						valid.garantiaMeses(),
						valid.destaque(),
						valid.images(),
						valid.imageThumbnails());

		mockMvc
				.perform(
						post("/api/backoffice/cars")
								.with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(invalid)))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("preco")));
	}

	/**
	 * C22: a price that would overflow {@code numeric(12,2)} is caught by validation (400).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsACarWithAPriceLargeEnoughToOverflowTheDatabaseColumn() throws Exception {
		CarRequest valid = validCarRequest();
		CarRequest invalid =
				new CarRequest(
						valid.marca(),
						valid.modelo(),
						valid.ano(),
						new BigDecimal("99999999999"),
						valid.km(),
						valid.cor(),
						valid.combustivel(),
						valid.transmissao(),
						valid.origem(),
						valid.descricao(),
						valid.observacoes(),
						valid.precoCompra(),
						valid.dataCompra(),
						valid.isConsignacao(),
						valid.partnerId(),
						valid.commissionValue(),
						valid.garantiaMeses(),
						valid.destaque(),
						valid.images(),
						valid.imageThumbnails());

		mockMvc
				.perform(
						post("/api/backoffice/cars")
								.with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(invalid)))
				.andExpect(status().isBadRequest());
	}

	/**
	 * C23: markup in {@code marca} is a 400 (the public site renders it).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsACarWithUnsanitisedMarkupInMarca() throws Exception {
		CarRequest valid = validCarRequest();
		CarRequest invalid =
				new CarRequest(
						"<script>alert(1)</script>",
						valid.modelo(),
						valid.ano(),
						valid.preco(),
						valid.km(),
						valid.cor(),
						valid.combustivel(),
						valid.transmissao(),
						valid.origem(),
						valid.descricao(),
						valid.observacoes(),
						valid.precoCompra(),
						valid.dataCompra(),
						valid.isConsignacao(),
						valid.partnerId(),
						valid.commissionValue(),
						valid.garantiaMeses(),
						valid.destaque(),
						valid.images(),
						valid.imageThumbnails());

		mockMvc
				.perform(
						post("/api/backoffice/cars")
								.with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(invalid)))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("marca")));
	}

	/**
	 * C24: malformed JSON is a 400 in the {@code ApiError} envelope.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void malformedJsonBodyRespondsWithTheStandardErrorEnvelope() throws Exception {
		mockMvc
				.perform(
						post("/api/backoffice/cars")
								.with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content("{not-valid-json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").isNotEmpty());
	}

	/**
	 * C25: a non-UUID path variable is a 400 in the {@code ApiError} envelope.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aNonUuidPathVariableRespondsWithTheStandardErrorEnvelope() throws Exception {
		mockMvc
				.perform(
						get("/api/backoffice/cars/not-a-uuid")
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").isNotEmpty());
	}

	/**
	 * C26: an unknown {@code sort} property is a 400 (now rejected by the allow-list, AC D.3).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void anUnknownSortPropertyRespondsWith400InsteadOf500() throws Exception {
		mockMvc
				.perform(
						get("/api/backoffice/cars?sort=nosuchfield")
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.message").isNotEmpty());
	}

	/**
	 * C27: an unmapped route inside the prefix is a 404, not a 500.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void anUnmappedRouteInsideCarsRespondsWith404NotAGenericServerError() throws Exception {
		Car car = carRepository.saveAndFlush(aPersistedCar().build());

		mockMvc
				.perform(
						get("/api/backoffice/cars/{id}/does-not-exist", car.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404));
	}

	/**
	 * C28: {@code DELETE} on the collection is a 405 (and stays so after TASK-010, which only maps
	 * {@code DELETE /{id}}).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aMethodNotMappedOnCarsRespondsWith405NotAGenericServerError() throws Exception {
		mockMvc
				.perform(delete("/api/backoffice/cars").with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE))))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.status").value(405));
	}

	/**
	 * C29: a content type the API cannot read is a 415, not a 500.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void anUnsupportedContentTypeOnCreateRespondsWith415NotAGenericServerError() throws Exception {
		mockMvc
				.perform(
						post("/api/backoffice/cars")
								.with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE)))
								.contentType(MediaType.TEXT_PLAIN)
								.content("not json"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.status").value(415));
	}

	// ---------------------------------------------------------------------------------------------
	// New in TASK-003 (AC I.4)
	// ---------------------------------------------------------------------------------------------

	/**
	 * AC B.1: a valid token without any role (what Spring Security 7 gives a bearer token with no
	 * roles claim: {@code FACTOR_BEARER} plus scopes) is a 403 on listing and on creating, and
	 * creates nothing.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aTokenWithoutARoleIsForbiddenToListAndToCreate() throws Exception {
		carRepository.saveAndFlush(aPersistedCar().precoCompra(new BigDecimal("18000.00")).build());

		mockMvc
				.perform(get("/api/backoffice/cars").with(withoutRole()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.message").value("Autenticado, mas sem a role exigida para esta operacao"))
				.andExpect(jsonPath("$.content").doesNotExist());
		mockMvc
				.perform(postJson("/api/backoffice/cars", validCarRequest()).with(withoutRole()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Autenticado, mas sem a role exigida para esta operacao"));

		assertThat(carRepository.count()).isEqualTo(1);
	}

	/**
	 * AC B.1, positive side: {@code ADMIN} and {@code USER} both list (200) and create (201).
	 *
	 * @param role the role authority
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@ParameterizedTest
	@ValueSource(strings = {ADMIN_ROLE, USER_ROLE})
	void adminAndUserCanListAndCreate(String role) throws Exception {
		mockMvc
				.perform(get("/api/backoffice/cars").with(jwt().authorities(new SimpleGrantedAuthority(role))))
				.andExpect(status().isOk());
		mockMvc
				.perform(postJson("/api/backoffice/cars", validCarRequest())
						.with(jwt().authorities(new SimpleGrantedAuthority(role))))
				.andExpect(status().isCreated());
	}

	/**
	 * AC B.2: the role check runs after the body is read and validated, so a token without a role
	 * and an invalid body gets the 400 (it only reveals our own validation rules), and nothing is
	 * stored.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aTokenWithoutARoleAndAnInvalidBodyGetsTheValidationError() throws Exception {
		ObjectNode body = validBody();
		body.put("preco", 50);

		mockMvc
				.perform(postJson("/api/backoffice/cars", body).with(withoutRole()))
				.andExpect(status().isBadRequest());
		assertThat(carRepository.count()).isZero();
	}

	/**
	 * AC C.2/C.3: a body without {@code destaque}, {@code isConsignacao}, {@code images}, {@code
	 * imageThumbnails} and {@code observacoes} (the real {@code dcbo} form never sends {@code
	 * destaque}) is a 201 with the documented defaults.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aBodyWithoutTheOptionalBooleansImagesAndObservacoesGetsTheDefaults() throws Exception {
		ObjectNode body = validBody();
		body.remove(List.of("destaque", "isConsignacao", "images", "imageThumbnails", "observacoes", "garantiaMeses"));

		JsonNode created = createCar(body);

		assertThat(created.get("destaque").asBoolean()).isFalse();
		assertThat(created.get("isConsignacao").asBoolean()).isFalse();
		assertThat(created.get("images")).isEmpty();
		assertThat(created.get("imageThumbnails")).isEmpty();
		assertThat(created.get("observacoes").isNull()).isTrue();
		assertThat(created.get("garantiaMeses").asInt()).isZero();
		Car stored = carRepository.findById(UUID.fromString(created.get("id").asString())).orElseThrow();
		assertThat(stored.isDestaque()).isFalse();
		assertThat(stored.isConsignacao()).isFalse();
		assertThat(stored.getObservacoes()).isNull();
	}

	/**
	 * AC C.3/F: {@code observacoes} made only of spaces is stored as {@code null}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void blankObservacoesIsStoredAsNull() throws Exception {
		ObjectNode body = validBody();
		body.put("observacoes", "   ");

		JsonNode created = createCar(body);

		assertThat(created.get("observacoes").isNull()).isTrue();
		assertThat(carRepository.findById(UUID.fromString(created.get("id").asString())).orElseThrow().getObservacoes())
				.isNull();
	}

	/**
	 * AC F: {@code observacoes} with text is in the back-office response and on the public car page
	 * ({@code GET /api/cars/{id}}, no token).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void observacoesWithTextIsReturnedAndShownOnThePublicSite() throws Exception {
		ObjectNode body = validBody();
		body.put("observacoes", "Revisão feita, pneus novos");

		JsonNode created = createCar(body);

		assertThat(created.get("observacoes").asString()).isEqualTo("Revisão feita, pneus novos");
		mockMvc
				.perform(get("/api/cars/{id}", created.get("id").asString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.observacoes").value("Revisão feita, pneus novos"));
	}

	/**
	 * AC C.4: state keys in the create body ({@code vendido}, {@code reservado}, {@code precoVenda},
	 * {@code dataVenda}, {@code clienteId}, {@code id}, the alias {@code consignacao}) are ignored.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void stateKeysInTheCreateBodyHaveNoEffect() throws Exception {
		Client client = clientRepository.saveAndFlush(Client.builder().name("Ana").phone("912345678").build());
		UUID sentId = UUID.randomUUID();
		ObjectNode body = validBody();
		body.remove("isConsignacao");
		body.put("vendido", true);
		body.put("reservado", true);
		body.put("precoVenda", 24000);
		body.put("dataVenda", "2026-09-01T10:00:00Z");
		body.put("clienteId", client.getId().toString());
		body.put("id", sentId.toString());
		body.put("consignacao", true);

		JsonNode created = createCar(body);

		assertThat(created.get("vendido").asBoolean()).isFalse();
		assertThat(created.get("reservado").asBoolean()).isFalse();
		assertThat(created.get("isConsignacao").asBoolean()).isFalse();
		assertThat(created.get("precoVenda").isNull()).isTrue();
		assertThat(created.get("dataVenda").isNull()).isTrue();
		assertThat(created.get("clienteId").isNull()).isTrue();
		assertThat(created.get("id").asString()).isNotEqualTo(sentId.toString());
		Car stored = carRepository.findById(UUID.fromString(created.get("id").asString())).orElseThrow();
		assertThat(stored.isVendido()).isFalse();
		assertThat(stored.getClient()).isNull();
	}

	/**
	 * AC C.1: inputs longer than their column, a {@code null} photo URL and more than 60 photos are a
	 * 400 naming the field (they used to be a 409 from the database, or accepted), and nothing is
	 * stored. Only the {@code field:} prefix is asserted: the Bean Validation text depends on the
	 * JVM's {@code Locale}.
	 *
	 * @param field the field the message must start with
	 * @param invalidation what makes the otherwise valid body invalid
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("inputsBeyondTheirColumn")
	void inputBeyondItsColumnOrPhotoLimitIsA400NamingTheField(String field, Consumer<ObjectNode> invalidation)
			throws Exception {
		ObjectNode body = validBody();
		invalidation.accept(body);

		mockMvc
				.perform(postJson("/api/backoffice/cars", body).with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith(field + ": ")));
		assertThat(carRepository.count()).isZero();
	}

	static Stream<Arguments> inputsBeyondTheirColumn() {
		String url1001 = "https://img/" + "a".repeat(1001 - "https://img/".length());
		return Stream.of(
				Arguments.of("combustivel", (Consumer<ObjectNode>) body -> body.put("combustivel", "x".repeat(51))),
				Arguments.of("transmissao", (Consumer<ObjectNode>) body -> body.put("transmissao", "x".repeat(51))),
				Arguments.of("origem", (Consumer<ObjectNode>) body -> body.put("origem", "x".repeat(101))),
				Arguments.of("observacoes", (Consumer<ObjectNode>) body -> body.put("observacoes", "x".repeat(1001))),
				Arguments.of("images[0]", (Consumer<ObjectNode>) body -> body.putArray("images").add(url1001)),
				Arguments.of("images[1]", (Consumer<ObjectNode>) body -> body.putArray("images").add("https://img/1.jpg").addNull()),
				Arguments.of("images", (Consumer<ObjectNode>) body -> fillUrls(body.putArray("images"), 61)),
				Arguments.of("imageThumbnails[0]", (Consumer<ObjectNode>) body -> body.putArray("imageThumbnails").add(url1001)),
				Arguments.of("imageThumbnails", (Consumer<ObjectNode>) body -> fillUrls(body.putArray("imageThumbnails"), 61)));
	}

	/**
	 * AC C.1, boundary on the accepted side: exactly 60 photos and URLs of exactly 1000 characters
	 * are stored, in order.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void sixtyPhotosWithThousandCharacterUrlsAreAccepted() throws Exception {
		ObjectNode body = validBody();
		ArrayNode images = body.putArray("images");
		for (int i = 0; i < 60; i++) {
			String prefix = "https://img/" + i + "/";
			images.add(prefix + "a".repeat(1000 - prefix.length()));
		}
		body.remove("imageThumbnails");

		JsonNode created = createCar(body);

		assertThat(created.get("images")).hasSize(60);
		assertThat(created.get("images").get(59).asString()).startsWith("https://img/59/").hasSize(1000);
		assertThat(created.get("imageThumbnails")).hasSize(60);
	}

	/**
	 * AC C.5: the back-office car has exactly the 28 keys, {@code isConsignacao} included, on create,
	 * get and listing.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void theBackOfficeCarHasExactlyTheTwentyEightKeys() throws Exception {
		JsonNode created = createCar(validBody());
		JsonNode fetched = getJson(get("/api/backoffice/cars/{id}", created.get("id").asString()));
		JsonNode listing = getJson(get("/api/backoffice/cars"));

		assertThat(fieldNames(created)).containsExactlyInAnyOrderElementsOf(BACK_OFFICE_CAR_KEYS);
		assertThat(fieldNames(fetched)).containsExactlyInAnyOrderElementsOf(BACK_OFFICE_CAR_KEYS);
		assertThat(fieldNames(listing.get("content").get(0))).containsExactlyInAnyOrderElementsOf(BACK_OFFICE_CAR_KEYS);
		assertThat(fieldNames(listing.get("page")))
				.containsExactlyInAnyOrder("size", "number", "totalElements", "totalPages");
	}

	/**
	 * AC D.2: the page size is capped at 100 ({@code spring.data.web.pageable.max-page-size}), and the
	 * default is 50.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void thePageSizeIsCappedAtOneHundredAndDefaultsToFifty() throws Exception {
		mockMvc
				.perform(get("/api/backoffice/cars?size=1000").with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.size").value(100));
		mockMvc
				.perform(get("/api/backoffice/cars").with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page.size").value(50));
	}

	/**
	 * AC D.3: every scalar property of the allow-list sorts (200).
	 *
	 * @param property a property of the allow-list, as a literal
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@ParameterizedTest
	@ValueSource(strings = {
		"id", "createdAt", "updatedAt", "marca", "modelo", "ano", "preco", "km", "cor", "combustivel",
		"transmissao", "origem", "garantiaMeses", "vendido", "reservado", "destaque", "dataCompra",
		"dataVenda", "precoCompra", "precoVenda", "commissionValue"
	})
	void everyAllowedSortPropertySorts(String property) throws Exception {
		carRepository.saveAndFlush(aPersistedCar().build());

		mockMvc
				.perform(get("/api/backoffice/cars")
						.param("sort", property + ",asc")
						.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1));
	}

	/**
	 * AC D.3: associations, paths through them and unknown properties are a 400 with the property in
	 * the message, before any query (a sort through {@code images} repeated the same car on several
	 * pages in {@code dcbo-backend}).
	 *
	 * @param property a property outside the allow-list
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@ParameterizedTest
	@ValueSource(strings = {"partner", "partner.name", "client.name", "images", "images.url", "nosuchfield"})
	void sortPropertiesOutsideTheAllowListAreA400(String property) throws Exception {
		Car car = aPersistedCar().build();
		car.addImage(CarImage.builder().url("https://img/a.jpg").position(0).build());
		car.addImage(CarImage.builder().url("https://img/b.jpg").position(1).build());
		carRepository.saveAndFlush(car);

		mockMvc
				.perform(get("/api/backoffice/cars")
						.param("sort", property)
						.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Campo de ordenacao desconhecido: " + property));
	}

	/**
	 * AC D.1: cars with the same {@code createdAt} are ordered by {@code id} (descending), so pages
	 * are stable: two pages of 2 and one of 1 return the 5 cars once each, in that order. Inserted
	 * with ascending ids, so a missing tie-breaker shows up as the heap order.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void carsCreatedAtTheSameInstantArePagedByIdWithoutRepeats() throws Exception {
		Timestamp sameInstant = Timestamp.from(Instant.parse("2026-10-01T12:00:00Z"));
		List<String> ascendingIds = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			String id = "00000000-0000-0000-0000-00000000000" + i;
			ascendingIds.add(id);
			jdbcTemplate.update(
					"INSERT INTO cars (id, marca, modelo, ano, preco, km, cor, combustivel, transmissao, origem,"
							+ " created_at, updated_at) VALUES (?::uuid, 'Audi', 'A4', 2019, 22000, 80000, 'Branco',"
							+ " 'Gasolina', 'Manual', 'stand', ?, ?)",
					id, sameInstant, sameInstant);
		}
		jdbcTemplate.execute("ANALYZE cars");

		List<String> paged = new ArrayList<>();
		for (int page = 0; page < 3; page++) {
			JsonNode content = getJson(get("/api/backoffice/cars?size=2&page=" + page)).get("content");
			content.forEach(car -> paged.add(car.get("id").asString()));
		}

		assertThat(paged).containsExactlyElementsOf(ascendingIds.reversed());
	}

	/**
	 * AC D.4: listing 100 cars with a partner, a client and 3 photos each runs at most 5 statements
	 * (cars, count, 2 batches of photos, the active-user lookup): partner and client are never
	 * loaded. Fails if someone maps a partner field into the DTO (one query per car).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listingOneHundredCarsRunsAtMostFiveStatements() throws Exception {
		Partner partner = partnerRepository.saveAndFlush(Partner.builder().name("Auto Silva").build());
		Client client = clientRepository.saveAndFlush(Client.builder().name("Ana").phone("912345678").build());
		List<Car> cars = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			Car car = aPersistedCar().modelo("Car " + i).partner(partner).client(client).consignacao(true).build();
			for (int position = 0; position < 3; position++) {
				car.addImage(CarImage.builder().url("https://img/" + i + "/" + position + ".jpg").position(position).build());
			}
			cars.add(car);
		}
		carRepository.saveAllAndFlush(cars);

		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();
		JsonNode listing;
		long statements;
		try {
			listing = getJson(get("/api/backoffice/cars?size=100"));
			statements = statistics.getPrepareStatementCount();
		} finally {
			statistics.setStatisticsEnabled(false);
		}

		assertThat(listing.get("content")).hasSize(100);
		assertThat(listing.get("content").get(0).get("partnerId").asString()).isEqualTo(partner.getId().toString());
		assertThat(listing.get("content").get(0).get("clienteId").asString()).isEqualTo(client.getId().toString());
		assertThat(listing.get("content").get(99).get("images")).hasSize(3);
		assertThat(statements).isPositive().isLessThanOrEqualTo(5);
	}

	/**
	 * AC A.2: {@code PATCH .../highlight} with {@code destaque} missing is a 400 and changes nothing.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void highlightWithoutDestaqueIsA400() throws Exception {
		Car car = carRepository.saveAndFlush(aPersistedCar().destaque(true).build());

		mockMvc
				.perform(patch("/api/backoffice/cars/{id}/highlight", car.getId())
						.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("destaque: ")));
		assertThat(carRepository.findById(car.getId()).orElseThrow().isDestaque()).isTrue();
	}

	/**
	 * AC A.2: reserve, release and highlight of a car that does not exist, and a create with a
	 * partner that does not exist, are 404s with the documented messages.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void unknownCarOrPartnerIsA404WithTheDocumentedMessage() throws Exception {
		UUID unknown = UUID.randomUUID();
		for (MockHttpServletRequestBuilder request : List.of(
				post("/api/backoffice/cars/{id}/reserve", unknown),
				post("/api/backoffice/cars/{id}/release-reservation", unknown),
				get("/api/backoffice/cars/{id}", unknown),
				patch("/api/backoffice/cars/{id}/highlight", unknown)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"destaque\":true}"))) {
			mockMvc
					.perform(request.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.message").value("Carro nao encontrado: " + unknown));
		}

		ObjectNode body = validBody();
		body.put("partnerId", unknown.toString());
		mockMvc
				.perform(postJson("/api/backoffice/cars", body).with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE))))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Parceiro nao encontrado: " + unknown));
		assertThat(carRepository.count()).isZero();
	}

	/**
	 * AC A.2: reserve and release are idempotent (200 twice in a row, same state).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void reserveAndReleaseAreIdempotent() throws Exception {
		Car car = carRepository.saveAndFlush(aPersistedCar().build());

		for (int i = 0; i < 2; i++) {
			mockMvc
					.perform(post("/api/backoffice/cars/{id}/reserve", car.getId())
							.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.reservado").value(true));
		}
		for (int i = 0; i < 2; i++) {
			mockMvc
					.perform(post("/api/backoffice/cars/{id}/release-reservation", car.getId())
							.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.reservado").value(false));
		}
	}

	/**
	 * AC E.1: the limit also applies to a car created featured, and counts sold featured cars too
	 * (parity with {@code dcbo-backend} and {@code dcbo/src/pages/highlights.js}); the refused car is
	 * not stored.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void creatingAFeaturedCarIsRefusedWhenEightAreFeaturedEvenIfSold() throws Exception {
		for (int i = 0; i < 8; i++) {
			carRepository.saveAndFlush(aPersistedCar().modelo("Sold " + i).vendido(true).destaque(true).build());
		}
		ObjectNode body = validBody();
		body.put("destaque", true);

		mockMvc
				.perform(postJson("/api/backoffice/cars", body).with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Limite de 8 carros em destaque atingido"));
		assertThat(carRepository.count()).isEqualTo(8);

		body.put("destaque", false);
		mockMvc
				.perform(postJson("/api/backoffice/cars", body).with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE))))
				.andExpect(status().isCreated());
	}

	/**
	 * AC E.1: with 7 featured, a car created featured is the 8th (201).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void creatingTheEighthFeaturedCarSucceeds() throws Exception {
		for (int i = 0; i < 7; i++) {
			carRepository.saveAndFlush(aPersistedCar().modelo("Featured " + i).destaque(true).build());
		}
		ObjectNode body = validBody();
		body.put("destaque", true);

		JsonNode created = createCar(body);

		assertThat(created.get("destaque").asBoolean()).isTrue();
		assertThat(carRepository.countByDestaqueTrue()).isEqualTo(8);
	}

	/**
	 * AC E.1: asking {@code destaque: true} for a car already featured, with 8 featured, does not
	 * count against the limit (200).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void featuringAnAlreadyFeaturedCarAtTheLimitIsAccepted() throws Exception {
		Car featured = null;
		for (int i = 0; i < 8; i++) {
			featured = carRepository.saveAndFlush(aPersistedCar().modelo("Featured " + i).destaque(true).build());
		}

		mockMvc
				.perform(patch("/api/backoffice/cars/{id}/highlight", featured.getId())
						.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"destaque\":true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.destaque").value(true));
		assertThat(carRepository.countByDestaqueTrue()).isEqualTo(8);
	}

	/**
	 * AC G.1: creating a consignment car with partner and commission, reserving, releasing and
	 * featuring a car sold to a client never touch the partner's and client's counters nor create
	 * transactions (those belong to sale and reversal, TASK-010).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void createReserveReleaseAndHighlightHaveNoFinancialSideEffects() throws Exception {
		Partner partner = partnerRepository.saveAndFlush(Partner.builder().name("Auto Silva").build());
		Client client = clientRepository.saveAndFlush(Client.builder().name("Ana").phone("912345678").build());
		ObjectNode body = validBody();
		body.put("isConsignacao", true);
		body.put("partnerId", partner.getId().toString());
		body.put("commissionValue", 750);
		createCar(body);
		Car withClient = carRepository.saveAndFlush(aPersistedCar().client(client).build());

		for (String action : List.of("reserve", "release-reservation")) {
			mockMvc
					.perform(post("/api/backoffice/cars/{id}/" + action, withClient.getId())
							.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
					.andExpect(status().isOk());
		}
		mockMvc
				.perform(patch("/api/backoffice/cars/{id}/highlight", withClient.getId())
						.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"destaque\":true}"))
				.andExpect(status().isOk());

		Partner storedPartner = partnerRepository.findById(partner.getId()).orElseThrow();
		assertThat(storedPartner.getCarsCount()).isZero();
		assertThat(storedPartner.getTotalCommission()).isEqualByComparingTo("0.00");
		assertThat(clientRepository.findById(client.getId()).orElseThrow().getPurchasesCount()).isZero();
		assertThat(transactionRepository.count()).isZero();
	}

	/**
	 * AC G.2: a car created by the back-office with every internal field it accepts filled (purchase
	 * price and date, consignment, partner, commission) is on the public detail page, listing and
	 * highlights with exactly the 18 public keys, nothing internal.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aCarCreatedByTheBackOfficeShowsOnlyThePublicKeysOnThePublicSite() throws Exception {
		Partner partner = partnerRepository.saveAndFlush(Partner.builder().name("Auto Silva").build());
		ObjectNode body = validBody();
		body.put("observacoes", "Revisão feita");
		body.put("destaque", true);
		body.put("isConsignacao", true);
		body.put("partnerId", partner.getId().toString());
		body.put("commissionValue", 750);
		body.put("precoCompra", 18000);
		body.put("dataCompra", "2026-07-15");
		String id = createCar(body).get("id").asString();

		JsonNode detail = objectMapper.readTree(mockMvc.perform(get("/api/cars/{id}", id))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		JsonNode listing = objectMapper.readTree(mockMvc.perform(get("/api/cars"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		JsonNode highlights = objectMapper.readTree(mockMvc.perform(get("/api/cars/highlights"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

		assertThat(fieldNames(detail)).containsExactlyInAnyOrderElementsOf(PUBLIC_CAR_KEYS);
		assertThat(listing.get("content")).hasSize(1);
		assertThat(fieldNames(listing.get("content").get(0))).containsExactlyInAnyOrderElementsOf(PUBLIC_CAR_KEYS);
		assertThat(highlights).hasSize(1);
		assertThat(fieldNames(highlights.get(0))).containsExactlyInAnyOrderElementsOf(PUBLIC_CAR_KEYS);
		assertThat(detail.get("observacoes").asString()).isEqualTo("Revisão feita");
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------

	/** A bearer token with no role: what Spring Security 7 builds when the roles claim is empty. */
	private static RequestPostProcessor withoutRole() {
		return jwt().authorities(new SimpleGrantedAuthority("FACTOR_BEARER"), new SimpleGrantedAuthority("SCOPE_openid"));
	}

	private ObjectNode validBody() {
		return objectMapper.valueToTree(validCarRequest());
	}

	private MockHttpServletRequestBuilder postJson(String path, Object body) {
		return post(path).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
	}

	/**
	 * Creates a car as {@code ADMIN}, expecting 201.
	 *
	 * @param body the request body
	 * @return the parsed response
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	private JsonNode createCar(Object body) throws Exception {
		String response = mockMvc
				.perform(postJson("/api/backoffice/cars", body).with(jwt().authorities(new SimpleGrantedAuthority(ADMIN_ROLE))))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse()
				.getContentAsString();
		return objectMapper.readTree(response);
	}

	/**
	 * Performs a back-office request as {@code USER}, expecting 200.
	 *
	 * @param request the request
	 * @return the parsed response
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	private JsonNode getJson(MockHttpServletRequestBuilder request) throws Exception {
		String response = mockMvc
				.perform(request.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();
		return objectMapper.readTree(response);
	}

	private static List<String> fieldNames(JsonNode node) {
		return new ArrayList<>(node.propertyNames());
	}

	private static void fillUrls(ArrayNode array, int count) {
		for (int i = 0; i < count; i++) {
			array.add("https://img/" + i + ".jpg");
		}
	}
}
