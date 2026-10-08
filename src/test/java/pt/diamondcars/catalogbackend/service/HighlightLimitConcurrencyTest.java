package pt.diamondcars.catalogbackend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import pt.diamondcars.catalogbackend.web.dto.CarRequest;
import pt.diamondcars.catalogbackend.web.dto.HighlightRequest;
import pt.diamondcars.catalogbackend.web.exception.HighlightLimitExceededException;

/**
 * Proves, deterministically and against real PostgreSQL, that the 8-car highlight limit holds under
 * concurrent requests (TASK-003, AC E.3/E.4).
 *
 * <p>The gate: a separate connection holds {@code LOCK TABLE cars IN SHARE MODE}, which lets every
 * read through and makes every write to {@code cars} wait. The requests run in threads against
 * {@link BackOfficeCarService}; the test waits until {@code pg_stat_activity} shows every one of them
 * waiting on a lock (so all of them have read and counted whatever they were going to read and
 * count), then commits the gate. Without the advisory lock all of them count the same number and
 * all of them write; with it they queue on the lock and each count sees the previous commit.
 *
 * <p>Own context with a pool of 20: 9 workers, the gate and the polling query do not fit in the
 * default 10. {@link DirtiesContext} closes it (and its 20 connections) right after this class:
 * the test container has PostgreSQL's default {@code max_connections} (100, 3 reserved), every
 * cached context keeps its pool open (Hikari fills it to the maximum), and the suite already has 9
 * contexts of 10. Kept cached, this one made the 10th context fail with {@code FATAL: sorry, too
 * many clients already} (measured in {@code mvnw clean verify}).
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=20")
@DirtiesContext
class HighlightLimitConcurrencyTest extends AbstractPostgresIntegrationTest {

	private static final Duration ALL_WAITING_DEADLINE = Duration.ofSeconds(20);

	@Autowired private BackOfficeCarService carService;
	@Autowired private CarRepository carRepository;
	@Autowired private DataSource dataSource;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanDatabase() {
		jdbcTemplate.update("DELETE FROM transactions");
		carRepository.deleteAllInBatch();
	}

	/**
	 * 7 featured, then 2 highlights and 1 featured create at the same time: one succeeds, two are
	 * refused, 8 featured (count-then-save gives 10).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void sevenFeaturedPlusThreeConcurrentRequestsEndWithEight() throws Exception {
		for (int i = 0; i < 7; i++) {
			carRepository.saveAndFlush(aCar("Featured " + i).destaque(true).build());
		}
		Car first = carRepository.saveAndFlush(aCar("Candidate 1").build());
		Car second = carRepository.saveAndFlush(aCar("Candidate 2").build());

		Outcome outcome = race(List.of(
				() -> carService.highlight(first.getId(), new HighlightRequest(true)),
				() -> carService.highlight(second.getId(), new HighlightRequest(true)),
				() -> carService.create(featuredCarRequest())));

		assertThat(carRepository.countByDestaqueTrue()).isEqualTo(8);
		assertThat(outcome.successes()).isEqualTo(1);
		assertThat(outcome.refusals()).isEqualTo(2);
	}

	/**
	 * Nothing featured, then 9 highlights at the same time: 8 succeed, 1 is refused, 8 featured
	 * ({@code SELECT ... FOR UPDATE} of the featured cars gives 9: there is no row to lock).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void zeroFeaturedPlusNineConcurrentHighlightsEndWithEight() throws Exception {
		List<Callable<Object>> requests = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			Car car = carRepository.saveAndFlush(aCar("Candidate " + i).build());
			requests.add(() -> carService.highlight(car.getId(), new HighlightRequest(true)));
		}

		Outcome outcome = race(requests);

		assertThat(carRepository.countByDestaqueTrue()).isEqualTo(8);
		assertThat(outcome.successes()).isEqualTo(8);
		assertThat(outcome.refusals()).isEqualTo(1);
	}

	/**
	 * Runs the requests concurrently behind the {@code SHARE} gate and releases them only once all
	 * are waiting on a lock.
	 *
	 * @param requests the service calls to race
	 * @return how many succeeded and how many were refused with 409
	 * @throws Exception if a request fails for any other reason, or never reaches the lock
	 */
	private Outcome race(List<Callable<Object>> requests) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(requests.size());
		try (Connection gate = dataSource.getConnection()) {
			gate.setAutoCommit(false);
			try (Statement statement = gate.createStatement()) {
				statement.execute("LOCK TABLE cars IN SHARE MODE");
			}
			List<Future<Boolean>> results = new ArrayList<>();
			try {
				for (Callable<Object> request : requests) {
					results.add(pool.submit(() -> {
						try {
							request.call();
							return true;
						} catch (HighlightLimitExceededException refused) {
							return false;
						}
					}));
				}
				awaitWaitingOnLocks(requests.size());
			} finally {
				gate.commit();
			}
			int successes = 0;
			for (Future<Boolean> result : results) {
				if (result.get(30, TimeUnit.SECONDS)) {
					successes++;
				}
			}
			return new Outcome(successes, requests.size() - successes);
		} finally {
			pool.shutdownNow();
		}
	}

	private void awaitWaitingOnLocks(int expected) throws InterruptedException {
		Instant deadline = Instant.now().plus(ALL_WAITING_DEADLINE);
		Integer waiting = 0;
		while (Instant.now().isBefore(deadline)) {
			waiting = jdbcTemplate.queryForObject(
					"SELECT count(*)::int FROM pg_stat_activity WHERE datname = current_database()"
							+ " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'",
					Integer.class);
			if (waiting != null && waiting == expected) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Only " + waiting + " of " + expected + " requests reached a lock wait");
	}

	private static Car.CarBuilder aCar(String modelo) {
		return Car.builder()
				.marca("Audi")
				.modelo(modelo)
				.ano(2019)
				.preco(new BigDecimal("22000.00"))
				.km(80000)
				.cor("Branco")
				.combustivel("Gasolina")
				.transmissao("Manual")
				.origem("Nacional");
	}

	private static CarRequest featuredCarRequest() {
		return new CarRequest(
				"BMW", "320d", 2020, new BigDecimal("25000.00"), 50000, "Preto", "Diesel", "Manual",
				"Nacional", null, null, null, null, null, null, null, null, true, null, null);
	}

	private record Outcome(int successes, int refusals) {}
}
