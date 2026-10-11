package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.partner.Partner;
import pt.diamondcars.catalogbackend.domain.partner.PartnerRepository;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import pt.diamondcars.catalogbackend.web.dto.CarRequest;
import pt.diamondcars.catalogbackend.web.dto.PartnerRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end tests of {@link PartnerController}, {@code PartnerService} and {@link
 * ApiExceptionHandler} through the real servlet filter chain (TASK-004), using {@link MockMvc}
 * against a real PostgreSQL container ({@link AbstractPostgresIntegrationTest}), ported from
 * {@code dcbo-backend}'s {@code PartnerControllerTest} with role tokens added (TASK-003 AC B.1).
 *
 * <p><b>Partition note</b>: 6 of the 18 tests in {@code dcbo-backend}'s version exercise {@code
 * POST /sell}, {@code POST /revert-sale} or a partner-moving {@code PUT /api/cars/{id}} —
 * endpoints TASK-010 creates, not this task. They are not ported here; TASK-010 ports them,
 * against the counter hooks this task already proved correct in isolation ({@code
 * PartnerServiceCountersTest}). This task only confirms, with {@code
 * creatingAConsignmentCarDoesNotChangeThePartnersCarsCount}, that creating a consignment car
 * (which TASK-003 already built) leaves the counters at zero — TASK-003 AC G.1 already measures
 * this for {@code create}/{@code reserve}/{@code release-reservation}/{@code highlight}, this
 * test only re-confirms it from the partner's side.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PartnerControllerTest extends AbstractPostgresIntegrationTest {

	private static final String USER_ROLE = "ROLE_USER";
	private static final String PATH = "/api/backoffice/partners";

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private PartnerRepository partnerRepository;
	@Autowired private CarRepository carRepository;

	/**
	 * Clears every row this class writes before each test, for the same reason {@code
	 * BackOfficeCarControllerTest#cleanDatabase()} does (shared, JVM-wide singleton container, no
	 * per-test rollback).
	 */
	@BeforeEach
	void cleanDatabase() {
		carRepository.deleteAll();
		partnerRepository.deleteAll();
	}

	private static PartnerRequest validPartnerRequest() {
		return new PartnerRequest("Parceiro Teste", "parceiro@example.com", "912345678", "Notas");
	}

	private static CarRequest consignmentCarRequestFor(UUID partnerId) {
		return new CarRequest(
				"Audi",
				"A4",
				2019,
				new BigDecimal("22000.00"),
				80000,
				"Branco",
				"Gasolina",
				"Manual",
				"stand",
				null,
				null,
				null,
				null,
				true,
				partnerId,
				new BigDecimal("500.00"),
				0,
				false,
				null,
				null);
	}

	private static Car.CarBuilder aConsignmentCarFor(Partner partner) {
		return Car.builder()
				.marca("Audi")
				.modelo("A4")
				.ano(2019)
				.preco(new BigDecimal("22000.00"))
				.km(80000)
				.cor("Branco")
				.combustivel("Gasolina")
				.transmissao("Manual")
				.origem("stand")
				.consignacao(true)
				.partner(partner)
				.commissionValue(new BigDecimal("500.00"));
	}

	/**
	 * A valid {@code POST} returns 201 with a {@code Location} header, and the created partner can
	 * then be read back with {@code GET /{id}} (200).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void createsAPartnerAndReadsItBackById() throws Exception {
		String location =
				mockMvc
						.perform(
								post(PATH)
										.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
										.contentType(MediaType.APPLICATION_JSON)
										.content(objectMapper.writeValueAsString(validPartnerRequest())))
						.andExpect(status().isCreated())
						.andExpect(header().string("Location", notNullValue()))
						.andExpect(jsonPath("$.name").value("Parceiro Teste"))
						.andExpect(jsonPath("$.carsCount").value(0))
						.andExpect(jsonPath("$.totalCommission").value(0))
						.andReturn()
						.getResponse()
						.getHeader("Location");

		mockMvc
				.perform(get(location).with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("parceiro@example.com"));
	}

	/**
	 * A partner name with only 2 characters (below the 3-character floor) responds 400, naming the
	 * field.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsAPartnerWithATooShortName() throws Exception {
		PartnerRequest valid = validPartnerRequest();
		PartnerRequest invalid = new PartnerRequest("Jo", valid.email(), valid.phone(), valid.notes());

		mockMvc
				.perform(
						post(PATH)
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(invalid)))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("name")));
	}

	/**
	 * A partner may be created without a phone (unlike a client's, it is optional).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void acceptsAPartnerWithoutAPhone() throws Exception {
		PartnerRequest valid = validPartnerRequest();
		PartnerRequest withoutPhone = new PartnerRequest(valid.name(), valid.email(), "", valid.notes());

		mockMvc
				.perform(
						post(PATH)
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(withoutPhone)))
				.andExpect(status().isCreated());
	}

	/**
	 * {@code GET /{id}} for an id that does not exist responds 404.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void returns404ForAnUnknownPartnerId() throws Exception {
		mockMvc
				.perform(
						get(PATH + "/{id}", UUID.randomUUID())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isNotFound());
	}

	/**
	 * A {@code PUT /{id}} replaces every field with the given payload, without touching {@code
	 * carsCount}/{@code totalCommission}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void updatingAPartnerReplacesItsFieldsButNotItsCounters() throws Exception {
		Partner partner =
				partnerRepository.saveAndFlush(
						Partner.builder().name("Antigo").carsCount(2).totalCommission(new BigDecimal("100.00")).build());

		mockMvc
				.perform(
						put(PATH + "/{id}", partner.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(validPartnerRequest())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Parceiro Teste"))
				.andExpect(jsonPath("$.carsCount").value(2))
				.andExpect(jsonPath("$.totalCommission").value(100.00));
	}

	/**
	 * {@code cars_count} only changes on a sale (TASK-010): creating a consignment car through
	 * {@code POST /api/backoffice/cars} (TASK-003) must not touch it — re-confirms TASK-003 AC G.1
	 * from the partner's side.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void creatingAConsignmentCarDoesNotChangeThePartnersCarsCount() throws Exception {
		Partner partner = partnerRepository.saveAndFlush(Partner.builder().name("Parceiro A").build());

		mockMvc
				.perform(
						post("/api/backoffice/cars")
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(consignmentCarRequestFor(partner.getId()))))
				.andExpect(status().isCreated());

		mockMvc
				.perform(
						get(PATH + "/{id}", partner.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.carsCount").value(0));
	}

	/**
	 * Phone numbers with spaces that the {@code dcbo} partner form accepts today (it strips spaces
	 * before validating) must not be rejected with 400 here.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void acceptsAPartnerPhoneWithSpacesLikeTheDcboFormDoes() throws Exception {
		PartnerRequest valid = validPartnerRequest();
		PartnerRequest spacedLocal =
				new PartnerRequest(valid.name(), valid.email(), "912 345 678", valid.notes());
		PartnerRequest spacedInternational =
				new PartnerRequest(valid.name(), valid.email(), "+351 912 345 678", valid.notes());

		mockMvc
				.perform(
						post(PATH)
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(spacedLocal)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.phone").value("912345678"));

		mockMvc
				.perform(
						post(PATH)
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(spacedInternational)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.phone").value("+351912345678"));
	}

	/**
	 * Counterpart of {@link #acceptsAPartnerPhoneWithSpacesLikeTheDcboFormDoes()}: a phone that
	 * does not match the Portuguese format even with spaces stripped is still rejected with 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsAPartnerPhoneThatIsInvalidEvenWithoutSpaces() throws Exception {
		PartnerRequest valid = validPartnerRequest();
		PartnerRequest invalid = new PartnerRequest(valid.name(), valid.email(), "812345678", valid.notes());

		mockMvc
				.perform(
						post(PATH)
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(invalid)))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("phone")));
	}

	/**
	 * {@code GET /{id}/cars} returns only cars with that exact {@code partner_id}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listsOnlyTheCarsConsignedByThatPartner() throws Exception {
		Partner partner = partnerRepository.saveAndFlush(Partner.builder().name("Parceiro D").build());
		Partner other = partnerRepository.saveAndFlush(Partner.builder().name("Parceiro E").build());
		Car ownCar = carRepository.saveAndFlush(aConsignmentCarFor(partner).build());
		carRepository.saveAndFlush(aConsignmentCarFor(other).modelo("Other").build());
		carRepository.saveAndFlush(
				Car.builder()
						.marca("BMW")
						.modelo("Standalone")
						.ano(2020)
						.preco(new BigDecimal("15000.00"))
						.km(1000)
						.cor("Preto")
						.combustivel("Diesel")
						.transmissao("Manual")
						.origem("stand")
						.build());

		mockMvc
				.perform(
						get(PATH + "/{id}/cars", partner.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(ownCar.getId().toString()));
	}

	/**
	 * Deleting a partner that still has a consignment car associated responds 409, and the partner
	 * still exists afterwards — mirrors {@code
	 * ClientControllerTest#deletingAClientWithACarAssociatedIsRejectedWith409()}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void deletingAPartnerWithAConsignmentCarAssociatedIsRejectedWith409() throws Exception {
		Partner partner = partnerRepository.saveAndFlush(Partner.builder().name("Parceiro F").build());
		carRepository.saveAndFlush(aConsignmentCarFor(partner).build());

		mockMvc
				.perform(
						delete(PATH + "/{id}", partner.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409));

		assertThat(partnerRepository.existsById(partner.getId())).isTrue();
	}

	/**
	 * Counterpart of {@link #deletingAPartnerWithAConsignmentCarAssociatedIsRejectedWith409()}: a
	 * partner without any car succeeds (204).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void deletingAPartnerWithoutCarsSucceeds() throws Exception {
		Partner partner = partnerRepository.saveAndFlush(Partner.builder().name("Parceiro G").build());

		mockMvc
				.perform(
						delete(PATH + "/{id}", partner.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isNoContent());

		assertThat(partnerRepository.existsById(partner.getId())).isFalse();
	}

	/**
	 * {@code GET} without an {@code Authorization} header responds 401.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listingPartnersWithoutATokenIsUnauthorized() throws Exception {
		mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
	}

	/**
	 * TASK-003 AC B.1 applied here: a valid token of the tenant without any role is forbidden,
	 * both to list and to create.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aTokenWithoutARoleIsForbiddenToListAndToCreate() throws Exception {
		mockMvc.perform(get(PATH).with(jwt())).andExpect(status().isForbidden());

		mockMvc
				.perform(
						post(PATH)
								.with(jwt())
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(validPartnerRequest())))
				.andExpect(status().isForbidden());
		assertThat(partnerRepository.count()).isZero();
	}

	/**
	 * {@code GET ?sort=<desconhecida>} responds 400 via the shared {@code
	 * PropertyReferenceException} handler (TASK-003 AC H) — {@code Partner} has no allow-list of
	 * its own (AC D.3 of this task).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void sortingByAnUnknownPropertyRespondsWith400InsteadOf500() throws Exception {
		mockMvc
				.perform(
						get(PATH + "?sort=nosuchfield,desc")
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("nosuchfield")));
	}
}
