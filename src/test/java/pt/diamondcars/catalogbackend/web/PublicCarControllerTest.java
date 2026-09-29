package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarImage;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
	@Autowired private DataSource dataSource;
	@Autowired private ObjectMapper objectMapper;

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

	/**
	 * Regression test for TASK-014 review r1, IMPORTANTE 1: with many cars sharing the exact same
	 * {@code created_at} — forced here with a raw {@code UPDATE} via {@link JdbcTemplate}, since
	 * {@code @CreationTimestamp} makes the column read-only through JPA, but realistic in
	 * production after a bulk sync/backfill inserts many rows in one transaction (the column
	 * defaults to the transaction start) — sweeping every page of {@code GET /api/cars} must see
	 * each car exactly once. Before {@code CarQueryService#DEFAULT_SORT} gained an {@code id}
	 * tiebreaker, tied rows could be reordered between the different {@code OFFSET} queries the
	 * sweep issues, producing duplicates and omissions.
	 *
	 * <p>{@code totalCars} is deliberately 150, not a smaller round number: TASK-014 review r2
	 * measured that with only 25 tied rows Postgres 16 (via Testcontainers) happens to return a
	 * stable relative order across {@code OFFSET} queries regardless of the {@code id} tiebreaker,
	 * so the test passed 3/3 even with the tiebreaker removed and did not actually protect against
	 * the regression it targets. 150 was confirmed to fail deterministically (141/150 correct, 2/2
	 * runs) without the tiebreaker and to pass with it.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void paginationVisitsEveryCarExactlyOnceWhenManyShareTheSameCreatedAt() throws Exception {
		int totalCars = 150;
		for (int i = 0; i < totalCars; i++) {
			carRepository.saveAndFlush(aCar().modelo("Tied " + i).build());
		}
		new JdbcTemplate(dataSource).update("UPDATE cars SET created_at = now()");

		Set<String> seenIds = new HashSet<>();
		int pageSize = 7;
		int page = 0;
		int totalPages = Integer.MAX_VALUE;
		while (page < totalPages) {
			MvcResult result =
					mockMvc
							.perform(
									get("/api/cars")
											.param("page", String.valueOf(page))
											.param("size", String.valueOf(pageSize)))
							.andExpect(status().isOk())
							.andReturn();
			JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
			totalPages = body.get("totalPages").asInt();
			for (JsonNode carNode : body.get("content")) {
				seenIds.add(carNode.get("id").asString());
			}
			page++;
		}

		assertThat(seenIds).hasSize(totalCars);
	}

	/**
	 * Regression test for TASK-014 review r1, IMPORTANTE 2: an out-of-range {@code page} — negative,
	 * or large enough that {@code page * size} would overflow the offset {@code int} — must never
	 * surface as a 500. {@code CarQueryService#resolvePage} clamps it instead, the same way {@code
	 * size} is already clamped (ASSUNÇÃO in {@code backlog/tasks/TASK-014.md}, {@code ## Notas}).
	 *
	 * <p>More cars than the default {@code size} are inserted, and the ids returned for {@code
	 * page=-1} are compared against the ids returned for {@code page=0} (TASK-014 review r2,
	 * SUGESTÃO 1) — with only a single car, {@code $.page == 0} cannot tell "clamped to page 0"
	 * apart from "returned some other page that happened to contain the same car".
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void outOfRangePageIsClampedInsteadOfFailingWith500() throws Exception {
		for (int i = 0; i < 15; i++) {
			carRepository.saveAndFlush(aCar().modelo("Clamp " + i).build());
		}

		String negativePageBody =
				mockMvc
						.perform(get("/api/cars").param("page", "-1"))
						.andExpect(status().isOk())
						.andExpect(jsonPath("$.page").value(0))
						.andReturn()
						.getResponse()
						.getContentAsString();
		String firstPageBody =
				mockMvc
						.perform(get("/api/cars").param("page", "0"))
						.andExpect(status().isOk())
						.andReturn()
						.getResponse()
						.getContentAsString();

		assertThat(extractIds(negativePageBody)).isEqualTo(extractIds(firstPageBody));

		mockMvc
				.perform(
						get("/api/cars")
								.param("page", String.valueOf(Integer.MAX_VALUE))
								.param("size", "60"))
				.andExpect(status().isOk());
	}

	/**
	 * Extracts the {@code content[].id} values, in response order, from a {@code GET /api/cars}
	 * JSON body.
	 *
	 * @param responseBody the raw JSON response body
	 * @return the ids in the order they appear in {@code content}
	 * @throws Exception propagated from {@link ObjectMapper#readTree}
	 */
	private List<String> extractIds(String responseBody) throws Exception {
		JsonNode body = objectMapper.readTree(responseBody);
		List<String> ids = new ArrayList<>();
		for (JsonNode carNode : body.get("content")) {
			ids.add(carNode.get("id").asString());
		}
		return ids;
	}
}
