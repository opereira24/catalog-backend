package pt.diamondcars.catalogbackend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.partner.Partner;
import pt.diamondcars.catalogbackend.domain.partner.PartnerRepository;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionRepository;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import pt.diamondcars.catalogbackend.web.dto.CarRequest;
import pt.diamondcars.catalogbackend.web.exception.ResourceInUseException;

/**
 * Proves, deterministically and against real PostgreSQL, the {@code FOR UPDATE} lock of {@code
 * PartnerService#delete} (TASK-015): deleting a partner and saving a car that references it at the
 * same time never ends with both operations successful and the car stripped of its partner by {@code
 * ON DELETE SET NULL}. The side that loses fails with the named exception, and the end state is the
 * winner's.
 *
 * <p>Each scenario pins one interleaving, which is why the {@code race()} gate of {@link
 * CarSaleConcurrencyTest} ({@code cars, clients, partners}) is not used: it stops the writer's
 * {@code UPDATE}/{@code INSERT} on {@code cars} before its foreign key check, and once it opens the
 * two run in an arbitrary order.
 *
 * <ul>
 *   <li>The deletion decides first (K-PD1, K-PD3): the gate is {@code LOCK TABLE partners IN SHARE
 *       MODE} only. The deletion's {@code SELECT ... FOR UPDATE} takes {@code ROW SHARE} and passes,
 *       its car check passes, and its {@code DELETE} waits on the gate. The writer then reaches its
 *       foreign key check ({@code FOR KEY SHARE} on the partner), which has to wait on the deletion's
 *       row lock.
 *   <li>The car is saved first (K-PD2): a separate connection runs the {@code UPDATE} the {@code PUT}
 *       runs and does not commit, as K8 of {@link CarSaleConcurrencyTest}; the service commits right
 *       after its flush, with no point to stop at between the foreign key check and the commit.
 * </ul>
 *
 * <p>Shares the cached context of the other plain {@code @SpringBootTest} classes: at most 2 workers,
 * the gate or writer connection and the polling query, inside the default pool of 10.
 */
@SpringBootTest
class PartnerDeleteConcurrencyTest extends AbstractPostgresIntegrationTest {

	private static final Duration WAITING_DEADLINE = Duration.ofSeconds(20);

	@Autowired private PartnerService partnerService;
	@Autowired private BackOfficeCarService carService;
	@Autowired private CarRepository carRepository;
	@Autowired private PartnerRepository partnerRepository;
	@Autowired private TransactionRepository transactionRepository;
	@Autowired private DataSource dataSource;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanDatabase() {
		jdbcTemplate.update("DELETE FROM leads");
		transactionRepository.deleteAllInBatch();
		carRepository.deleteAllInBatch();
		partnerRepository.deleteAllInBatch();
	}

	/**
	 * K-PD1: the deletion of partner P has locked P and found no car when a {@code PUT} puts car C
	 * (model A4, no partner) in consignment with P and model A6. The {@code PUT} waits on the lock and
	 * fails its foreign key check once the deletion commits: P is gone, C keeps no partner and model
	 * A4, the whole {@code PUT} rolled back (without the lock: both succeed, and C ends with model A6
	 * and no partner, cleared by {@code ON DELETE SET NULL}).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void kpd1TheDeletionDecidesFirstAndTheConcurrentPutOfACarWithThatPartnerFails() throws Exception {
		Partner partner = aPartner();
		Car car = carRepository.saveAndFlush(aCar().build());

		Outcomes outcomes = deletionDecidingFirst(partner, () -> carService.update(car.getId(), aConsignmentEdit(partner, "A6")));

		assertThat(outcomes.deletion()).isNull();
		assertThat(outcomes.writer()).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(partnerRepository.existsById(partner.getId())).isFalse();
		Car reloaded = carRepository.findById(car.getId()).orElseThrow();
		assertThat(reloaded.getPartner()).isNull();
		assertThat(reloaded.getModelo()).isEqualTo("A4");
		assertThat(outcomes.waitingBeforeTheGateOpened()).as("deletion and PUT both waiting on a lock").isEqualTo(2);
	}

	/**
	 * K-PD2: car C is saved in consignment with partner P (not committed yet) when P's deletion
	 * starts. The deletion waits on the lock, then sees C and is refused: P stays and C keeps P
	 * (without the lock, or with the car check before it: the check misses the uncommitted car, both
	 * succeed and C ends without partner).
	 *
	 * @throws Exception from the writing connection or the worker
	 */
	@Test
	void kpd2TheCarIsSavedFirstAndTheConcurrentDeletionOfItsPartnerIsRefused() throws Exception {
		Partner partner = aPartner();
		Car car = carRepository.saveAndFlush(aCar().build());
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Connection writer = dataSource.getConnection()) {
			writer.setAutoCommit(false);
			try (PreparedStatement statement =
					writer.prepareStatement("UPDATE cars SET partner_id = ?, is_consignacao = true WHERE id = ?")) {
				statement.setObject(1, partner.getId());
				statement.setObject(2, car.getId());
				assertThat(statement.executeUpdate()).isEqualTo(1);
			}
			Future<?> deletion;
			try {
				deletion = pool.submit(() -> partnerService.delete(partner.getId()));
				awaitWaitingOnLocks(1, null);
			} finally {
				writer.commit();
			}

			assertThat(outcomeOf(deletion)).isInstanceOf(ResourceInUseException.class);
			assertThat(partnerRepository.existsById(partner.getId())).isTrue();
			assertThat(jdbcTemplate.queryForObject("SELECT partner_id FROM cars WHERE id = ?", UUID.class, car.getId()))
					.isEqualTo(partner.getId());
		} finally {
			pool.shutdownNow();
		}
	}

	/**
	 * K-PD3: the deletion of partner P has locked P and found no car when a {@code POST} creates a car
	 * in consignment with P. The {@code POST} waits on the lock and fails its foreign key check once
	 * the deletion commits: P is gone and no car exists (without the lock: both succeed, and the new
	 * car has no partner).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void kpd3TheDeletionDecidesFirstAndTheConcurrentPostOfACarWithThatPartnerFails() throws Exception {
		Partner partner = aPartner();

		Outcomes outcomes = deletionDecidingFirst(partner, () -> carService.create(aConsignmentEdit(partner, "A4")));

		assertThat(outcomes.deletion()).isNull();
		assertThat(outcomes.writer()).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(partnerRepository.existsById(partner.getId())).isFalse();
		assertThat(carRepository.count()).isZero();
		assertThat(outcomes.waitingBeforeTheGateOpened()).as("deletion and POST both waiting on a lock").isEqualTo(2);
	}

	/**
	 * Per side, {@code null} if it succeeded or the exception it threw, and how many sessions were
	 * waiting on a lock right before the gate opened.
	 */
	private record Outcomes(Throwable deletion, Throwable writer, int waitingBeforeTheGateOpened) {}

	/**
	 * Runs the partner's deletion behind {@code LOCK TABLE partners IN SHARE MODE} until it waits on
	 * its {@code DELETE} (it has locked the row and checked for cars), then the writer; opens the gate
	 * once both wait on a lock, or once the writer has finished without waiting (what a deletion
	 * without {@code FOR UPDATE} lets it do, so the test reaches its assertions instead of the
	 * deadline).
	 *
	 * @param partner the partner to delete
	 * @param writer the car request that assigns this partner
	 * @return both outcomes and the lock waiters seen before the gate opened
	 * @throws Exception from the gate connection, or if a worker does not finish in time
	 */
	private Outcomes deletionDecidingFirst(Partner partner, Callable<?> writer) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try (Connection gate = dataSource.getConnection()) {
			gate.setAutoCommit(false);
			try (Statement statement = gate.createStatement()) {
				statement.execute("LOCK TABLE partners IN SHARE MODE");
			}
			Future<?> deletion;
			Future<?> writing;
			int waiting;
			try {
				deletion = pool.submit(() -> partnerService.delete(partner.getId()));
				awaitWaitingOnLocks(1, null);
				writing = pool.submit(writer);
				waiting = awaitWaitingOnLocks(2, writing);
			} finally {
				gate.commit();
			}
			return new Outcomes(outcomeOf(deletion), outcomeOf(writing), waiting);
		} finally {
			pool.shutdownNow();
		}
	}

	/**
	 * {@code null} if the task succeeded, or the exception it threw.
	 *
	 * @throws Exception if the task does not finish in time
	 */
	private static Throwable outcomeOf(Future<?> task) throws Exception {
		try {
			task.get(30, TimeUnit.SECONDS);
			return null;
		} catch (ExecutionException failed) {
			return failed.getCause();
		}
	}

	/**
	 * Polls {@code pg_stat_activity} until {@code expected} sessions wait on a lock, or until {@code
	 * finishedInstead} (if given) is done.
	 *
	 * @return the number of sessions waiting on a lock at the last poll
	 * @throws AssertionError if neither happens before the deadline
	 */
	private int awaitWaitingOnLocks(int expected, Future<?> finishedInstead) throws InterruptedException {
		Instant deadline = Instant.now().plus(WAITING_DEADLINE);
		int waiting = 0;
		while (Instant.now().isBefore(deadline)) {
			boolean finished = finishedInstead != null && finishedInstead.isDone();
			waiting = jdbcTemplate.queryForObject(
					"SELECT count(*)::int FROM pg_stat_activity WHERE datname = current_database()"
							+ " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'",
					Integer.class);
			if (waiting == expected || finished) {
				return waiting;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Only " + waiting + " of " + expected + " sessions reached a lock wait");
	}

	private Partner aPartner() {
		return partnerRepository.saveAndFlush(Partner.builder().name("Auto Silva").build());
	}

	/** A request for an {@link #aCar()} with this model, in consignment with this partner. */
	private static CarRequest aConsignmentEdit(Partner partner, String modelo) {
		return new CarRequest(
				"Audi", modelo, 2019, new BigDecimal("22000.00"), 80000, "Branco", "Gasolina", "Manual", "Nacional",
				null, null, null, null, true, partner.getId(), new BigDecimal("500.00"), 0, null, null, null);
	}

	private static Car.CarBuilder aCar() {
		return Car.builder()
				.marca("Audi")
				.modelo("A4")
				.ano(2019)
				.preco(new BigDecimal("22000.00"))
				.km(80000)
				.cor("Branco")
				.combustivel("Gasolina")
				.transmissao("Manual")
				.origem("Nacional");
	}
}
