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
import pt.diamondcars.catalogbackend.domain.client.Client;
import pt.diamondcars.catalogbackend.domain.client.ClientRepository;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import pt.diamondcars.catalogbackend.web.dto.ClientRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end tests of {@link ClientController}, {@code ClientService} and {@link
 * ApiExceptionHandler} through the real servlet filter chain (TASK-004), using {@link MockMvc}
 * against a real PostgreSQL container ({@link AbstractPostgresIntegrationTest}), ported from
 * {@code dcbo-backend}'s {@code ClientControllerTest} with role tokens added (TASK-003 AC B.1:
 * every back-office endpoint needs {@code ADMIN}/{@code USER}, not just "authenticated").
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ClientControllerTest extends AbstractPostgresIntegrationTest {

	private static final String USER_ROLE = "ROLE_USER";
	private static final String PATH = "/api/backoffice/clients";

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private ClientRepository clientRepository;
	@Autowired private CarRepository carRepository;

	/**
	 * Clears every row this class writes before each test, for the same reason {@code
	 * BackOfficeCarControllerTest#cleanDatabase()} does (shared, JVM-wide singleton container, no
	 * per-test rollback).
	 */
	@BeforeEach
	void cleanDatabase() {
		carRepository.deleteAll();
		clientRepository.deleteAll();
	}

	private static ClientRequest validClientRequest() {
		return new ClientRequest(
				"Cliente Teste", "cliente@example.com", "912345678", "123456789", "Rua Exemplo", "4700-000", "Notas");
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

	/**
	 * A valid {@code POST} returns 201 with a {@code Location} header, and the created client can
	 * then be read back with {@code GET /{id}} (200).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void createsAClientAndReadsItBackById() throws Exception {
		String location =
				mockMvc
						.perform(
								post(PATH)
										.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
										.contentType(MediaType.APPLICATION_JSON)
										.content(objectMapper.writeValueAsString(validClientRequest())))
						.andExpect(status().isCreated())
						.andExpect(header().string("Location", notNullValue()))
						.andExpect(jsonPath("$.name").value("Cliente Teste"))
						.andExpect(jsonPath("$.purchasesCount").value(0))
						.andReturn()
						.getResponse()
						.getHeader("Location");

		mockMvc
				.perform(get(location).with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nif").value("123456789"));
	}

	/**
	 * {@code POST} with an 8-digit NIF (one short of the required 9) responds 400, naming the
	 * field.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsAClientWithAnEightDigitNif() throws Exception {
		ClientRequest valid = validClientRequest();
		ClientRequest invalid =
				new ClientRequest(
						valid.name(), valid.email(), valid.phone(), "12345678", valid.address(), valid.postalCode(), valid.notes());

		mockMvc
				.perform(
						post(PATH)
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(invalid)))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("nif")));
	}

	/**
	 * A blank NIF is accepted (the field is optional), the positive control for {@link
	 * #rejectsAClientWithAnEightDigitNif()}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void acceptsAClientWithoutANif() throws Exception {
		ClientRequest valid = validClientRequest();
		ClientRequest withoutNif =
				new ClientRequest(
						valid.name(), valid.email(), valid.phone(), "", valid.address(), valid.postalCode(), valid.notes());

		mockMvc
				.perform(
						post(PATH)
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(withoutNif)))
				.andExpect(status().isCreated());
	}

	/**
	 * A missing/blank {@code phone} responds 400: unlike email/NIF/postal code, phone is required
	 * for a client.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void rejectsAClientWithoutAPhone() throws Exception {
		ClientRequest valid = validClientRequest();
		ClientRequest withoutPhone =
				new ClientRequest(
						valid.name(), valid.email(), "", valid.nif(), valid.address(), valid.postalCode(), valid.notes());

		mockMvc
				.perform(
						post(PATH)
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(withoutPhone)))
				.andExpect(status().isBadRequest())
				.andExpect(content().string(containsString("phone")));
	}

	/**
	 * {@code GET /{id}} for an id that does not exist responds 404.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void returns404ForAnUnknownClientId() throws Exception {
		mockMvc
				.perform(
						get(PATH + "/{id}", UUID.randomUUID())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isNotFound());
	}

	/**
	 * A {@code PUT /{id}} replaces every field with the given payload.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void updatingAClientReplacesItsFields() throws Exception {
		Client client =
				clientRepository.saveAndFlush(Client.builder().name("Antigo").phone("911111111").build());
		ClientRequest update = validClientRequest();

		mockMvc
				.perform(
						put(PATH + "/{id}", client.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE)))
								.contentType(MediaType.APPLICATION_JSON)
								.content(objectMapper.writeValueAsString(update)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Cliente Teste"))
				.andExpect(jsonPath("$.phone").value("912345678"));
	}

	/**
	 * Deleting a client that has a car associated (a purchase, {@code cars.client_id}) responds
	 * 409, and the client still exists afterwards.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void deletingAClientWithACarAssociatedIsRejectedWith409() throws Exception {
		Client client =
				clientRepository.saveAndFlush(
						Client.builder().name("Cliente Com Carro").phone("913333333").build());
		Car car = aPersistedCar().client(client).vendido(true).build();
		carRepository.saveAndFlush(car);

		mockMvc
				.perform(
						delete(PATH + "/{id}", client.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409));

		assertThat(clientRepository.existsById(client.getId())).isTrue();
	}

	/**
	 * Counterpart of {@link #deletingAClientWithACarAssociatedIsRejectedWith409()}: a client
	 * without any car succeeds (204).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void deletingAClientWithoutCarsSucceeds() throws Exception {
		Client client =
				clientRepository.saveAndFlush(Client.builder().name("Sem Carros").phone("914444444").build());

		mockMvc
				.perform(
						delete(PATH + "/{id}", client.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isNoContent());

		assertThat(clientRepository.existsById(client.getId())).isFalse();
	}

	/**
	 * {@code GET /{id}/cars} returns only the cars purchased by that client (matching {@code
	 * client_id}), backing {@code dcbo/src/components/client-cars-modal.js}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listsOnlyTheCarsPurchasedByThatClient() throws Exception {
		Client client = clientRepository.saveAndFlush(Client.builder().name("A").phone("915555555").build());
		Client other = clientRepository.saveAndFlush(Client.builder().name("B").phone("916666666").build());
		Car ownCar = carRepository.saveAndFlush(aPersistedCar().client(client).vendido(true).build());
		carRepository.saveAndFlush(aPersistedCar().modelo("Other").client(other).vendido(true).build());
		carRepository.saveAndFlush(aPersistedCar().modelo("Unsold").build());

		mockMvc
				.perform(
						get(PATH + "/{id}/cars", client.getId())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(ownCar.getId().toString()));
	}

	/**
	 * {@code GET /{id}/cars} for an id that does not exist responds 404, instead of an empty list
	 * that would be indistinguishable from "client exists but bought nothing".
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listingCarsForAnUnknownClientIdReturns404() throws Exception {
		mockMvc
				.perform(
						get(PATH + "/{id}/cars", UUID.randomUUID())
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isNotFound());
	}

	/**
	 * {@code GET ?search=...} filters by name/email/phone/NIF, case-insensitively.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void searchFiltersClientsByNameEmailPhoneOrNif() throws Exception {
		clientRepository.saveAndFlush(
				Client.builder().name("Joao Silva").email("joao@example.com").phone("917777777").build());
		clientRepository.saveAndFlush(
				Client.builder().name("Maria Costa").email("maria@example.com").phone("918888888").build());

		mockMvc
				.perform(
						get(PATH + "?search=joao")
								.with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].name").value("Joao Silva"));
	}

	/**
	 * {@code GET} without an {@code Authorization} header responds 401 — the global rule from
	 * {@code SecurityConfig}, re-verified against this business endpoint.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listingClientsWithoutATokenIsUnauthorized() throws Exception {
		mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized());
	}

	/**
	 * TASK-003 AC B.1 applied here: a valid token of the tenant without any role is forbidden,
	 * both to list and to create — "authenticated" alone is not enough.
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
								.content(objectMapper.writeValueAsString(validClientRequest())))
				.andExpect(status().isForbidden());
		assertThat(clientRepository.count()).isZero();
	}

	/**
	 * {@code GET} with no clients in the database returns an empty page, not an error.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listingClientsWithNoDataReturnsAnEmptyPage() throws Exception {
		mockMvc
				.perform(get(PATH).with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isArray())
				.andExpect(jsonPath("$.content.length()").value(0));
	}

	/**
	 * {@code GET ?sort=<desconhecida>} responds 400 via the shared {@code
	 * PropertyReferenceException} handler (TASK-003 AC H) — {@code Client} has no allow-list of its
	 * own (AC D.3 of this task).
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
