package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
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
import org.springframework.test.web.servlet.MockMvc;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarImage;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * End-to-end tests of {@link PublicCarController}, {@code CarQueryService} and {@link
 * ApiExceptionHandler} through the real servlet filter chain (TASK-014), using {@link MockMvc}
 * against a real PostgreSQL container ({@link AbstractPostgresIntegrationTest}, TASK-002).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PublicCarControllerTest extends AbstractPostgresIntegrationTest {

	@Autowired private MockMvc mockMvc;
	@Autowired private CarRepository carRepository;

	/**
	 * Clears every car written by a previous test, so tests never influence each other on the
	 * shared, JVM-wide container ({@link AbstractPostgresIntegrationTest}).
	 */
	@BeforeEach
	void cleanDatabase() {
		carRepository.deleteAll();
	}

	private static Car.CarBuilder aCar() {
		return Car.builder()
				.id(UUID.randomUUID())
				.marca("BMW")
				.modelo("320d")
				.ano(2020)
				.preco(new BigDecimal("25000.00"))
				.km(50000)
				.cor("Preto")
				.combustivel("Diesel");
	}

	/**
	 * Acceptance criterion: with 2 available cars and 1 sold, {@code GET /api/cars} with no
	 * authentication returns 200 with only the 2 available ones.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listsOnlyAvailableCarsByDefault() throws Exception {
		carRepository.saveAndFlush(aCar().modelo("Available 1").build());
		carRepository.saveAndFlush(aCar().modelo("Available 2").build());
		carRepository.saveAndFlush(aCar().modelo("Sold").vendido(true).build());

		mockMvc
				.perform(get("/api/cars"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.totalElements").value(2));
	}

	/**
	 * Acceptance criterion: {@code ?incluirVendidos=true} brings back all 3 cars, with the sold one
	 * last (available → reserved → sold ordering, no reserved car in this scenario).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void includesSoldCarsWhenAsked() throws Exception {
		carRepository.saveAndFlush(aCar().modelo("Available 1").build());
		carRepository.saveAndFlush(aCar().modelo("Available 2").build());
		carRepository.saveAndFlush(aCar().modelo("Sold").vendido(true).build());

		mockMvc
				.perform(get("/api/cars").param("incluirVendidos", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(3))
				.andExpect(jsonPath("$.content[2].modelo").value("Sold"));
	}

	/**
	 * Acceptance criterion: given a reserved, an available and a sold car, {@code content} is
	 * ordered available → reserved → sold.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void ordersAvailableThenReservedThenSold() throws Exception {
		carRepository.saveAndFlush(aCar().modelo("Reserved").reservado(true).build());
		carRepository.saveAndFlush(aCar().modelo("Sold").vendido(true).build());
		carRepository.saveAndFlush(aCar().modelo("Available").build());

		mockMvc
				.perform(get("/api/cars").param("incluirVendidos", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].modelo").value("Available"))
				.andExpect(jsonPath("$.content[1].modelo").value("Reserved"))
				.andExpect(jsonPath("$.content[2].modelo").value("Sold"));
	}

	/**
	 * Acceptance criterion: {@code ?size=500} is capped to at most 60 elements per page.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void capsPageSizeAt60() throws Exception {
		for (int i = 0; i < 65; i++) {
			carRepository.saveAndFlush(aCar().modelo("Car " + i).build());
		}

		mockMvc
				.perform(get("/api/cars").param("size", "500"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(60))
				.andExpect(jsonPath("$.content.length()").value(60));
	}

	/**
	 * Acceptance criterion: {@code GET /api/cars/{uuid-inexistente}} responds 404 with the
	 * structured {@code {status, error, message, path}} body.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void returns404ForAnUnknownCarId() throws Exception {
		UUID unknownId = UUID.randomUUID();

		mockMvc
				.perform(get("/api/cars/{id}", unknownId))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.error").value("Not Found"))
				.andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(unknownId.toString())))
				.andExpect(jsonPath("$.path").value("/api/cars/" + unknownId));
	}

	/**
	 * Acceptance criterion: {@code GET /api/cars/highlights} never returns more than 8 cars, nor any
	 * sold one, even when more than 8 highlighted+unsold cars exist.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void highlightsNeverExceedsEightAndExcludesSoldCars() throws Exception {
		for (int i = 0; i < 9; i++) {
			carRepository.saveAndFlush(aCar().modelo("Highlighted " + i).destaque(true).build());
		}
		carRepository.saveAndFlush(aCar().modelo("Highlighted but sold").destaque(true).vendido(true).build());
		carRepository.saveAndFlush(aCar().modelo("Not highlighted").build());

		mockMvc
				.perform(get("/api/cars/highlights"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(8))
				.andExpect(jsonPath("$[*].vendido").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(false))));
	}

	/**
	 * Acceptance criterion: every element carries {@code id} and {@code _id} with the same value,
	 * and {@code images} is ordered by position.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void exposesIdAndDeprecatedUnderscoreIdWithOrderedImages() throws Exception {
		Car car = aCar().build();
		car.addImage(CarImage.builder().url("https://img/2.jpg").position(2).build());
		car.addImage(CarImage.builder().url("https://img/1.jpg").position(1).build());
		carRepository.saveAndFlush(car);

		mockMvc
				.perform(get("/api/cars/{id}", car.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(car.getId().toString()))
				.andExpect(jsonPath("$._id").value(car.getId().toString()))
				.andExpect(jsonPath("$.images[0]").value("https://img/1.jpg"))
				.andExpect(jsonPath("$.images[1]").value("https://img/2.jpg"));
	}

	/**
	 * Acceptance criterion: none of the public endpoints require authentication — every one answers
	 * 200 to a plain, header-less request.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void everyPublicEndpointRespondsWithoutAuthentication() throws Exception {
		Car car = carRepository.saveAndFlush(aCar().build());

		mockMvc.perform(get("/api/cars")).andExpect(status().isOk());
		mockMvc.perform(get("/api/cars/highlights")).andExpect(status().isOk());
		mockMvc.perform(get("/api/cars/{id}", car.getId())).andExpect(status().isOk());
	}

	/**
	 * Acceptance criterion: a CORS preflight {@code OPTIONS} request from the configured origin
	 * ({@code http://localhost:3000}, the default of {@code app.cors.allowed-origins}) receives the
	 * expected CORS headers.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void respondsToACorsPreflightRequestFromTheAllowedOrigin() throws Exception {
		mockMvc
				.perform(
						options("/api/cars")
								.header("Origin", "http://localhost:3000")
								.header("Access-Control-Request-Method", "GET")
								.contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
	}

	/**
	 * Confirms {@link CarRepository#findAll} filtering also works through the API for the {@code
	 * marca} and price-range parameters (requirement 1), exercised together in one test to keep the
	 * suite focused on the acceptance criteria rather than every filter combination.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void filtersByMarcaAndPriceRange() throws Exception {
		carRepository.saveAndFlush(aCar().marca("BMW").preco(new BigDecimal("20000.00")).build());
		carRepository.saveAndFlush(aCar().marca("Audi").preco(new BigDecimal("30000.00")).build());

		mockMvc
				.perform(get("/api/cars").param("marca", "BMW"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].marca").value("BMW"));

		mockMvc
				.perform(get("/api/cars").param("precoMin", "25000"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].marca").value("Audi"));

		assertThat(carRepository.count()).isEqualTo(2);
	}
}
