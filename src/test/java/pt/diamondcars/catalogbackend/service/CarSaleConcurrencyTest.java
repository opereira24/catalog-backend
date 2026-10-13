package pt.diamondcars.catalogbackend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.client.Client;
import pt.diamondcars.catalogbackend.domain.client.ClientRepository;
import pt.diamondcars.catalogbackend.domain.partner.Partner;
import pt.diamondcars.catalogbackend.domain.partner.PartnerRepository;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionRepository;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import pt.diamondcars.catalogbackend.web.dto.CarRequest;
import pt.diamondcars.catalogbackend.web.dto.ClientRequest;
import pt.diamondcars.catalogbackend.web.dto.HighlightRequest;
import pt.diamondcars.catalogbackend.web.dto.LeadRequest;
import pt.diamondcars.catalogbackend.web.dto.LeadResponse;
import pt.diamondcars.catalogbackend.web.dto.PartnerRequest;
import pt.diamondcars.catalogbackend.web.dto.SellCarRequest;
import pt.diamondcars.catalogbackend.web.exception.CarAlreadySoldException;
import pt.diamondcars.catalogbackend.web.exception.ResourceInUseException;
import pt.diamondcars.catalogbackend.web.exception.ResourceNotFoundException;

/**
 * Proves, deterministically and against real PostgreSQL, the row locks of TASK-010 (AC C.1, D.6):
 * one test per scenario K1 to K10, each with the exact end state the locks guarantee. Without the
 * lock each scenario measures (AC C.1 table, and the mutations M1 to M9 in the PR) a lost write.
 *
 * <p>Same gate as {@link HighlightLimitConcurrencyTest} ({@code web/CLAUDE.md}, "Testar corridas"):
 * a separate connection holds {@code LOCK TABLE cars, clients, partners IN SHARE MODE}, which lets
 * reads and row locks ({@code SELECT ... FOR NO KEY UPDATE} takes {@code ROW SHARE}) through and
 * makes every write to those tables wait. The requests run in threads against the services; the
 * test waits until {@code pg_stat_activity} shows every one of them waiting on a lock (the gate, or a
 * row another worker holds), commits the gate, and asserts. A worker that writes before it decides
 * would be stopped by the gate before deciding, and the test would stop proving the race: here
 * every service reads and locks first and writes at the end ({@code saveAndFlush}/commit). The
 * {@code INSERT}/{@code DELETE} of {@code transactions} is not gated (it is not one of the three
 * tables) and only takes {@code FOR KEY SHARE} on the rows it references.
 *
 * <p>Own context with a pool of 20 and {@link DirtiesContext}, for the same reason as {@link
 * HighlightLimitConcurrencyTest}: at most 4 workers, the gate and the polling query, and the 20
 * connections are closed right after this class.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=20")
@DirtiesContext
class CarSaleConcurrencyTest extends AbstractPostgresIntegrationTest {

	private static final Duration ALL_WAITING_DEADLINE = Duration.ofSeconds(20);
	private static final BigDecimal PRICE = new BigDecimal("21000.00");

	@Autowired private BackOfficeCarService carService;
	@Autowired private PartnerService partnerService;
	@Autowired private ClientService clientService;
	@Autowired private LeadService leadService;
	@Autowired private CarRepository carRepository;
	@Autowired private PartnerRepository partnerRepository;
	@Autowired private ClientRepository clientRepository;
	@Autowired private TransactionRepository transactionRepository;
	@Autowired private DataSource dataSource;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanDatabase() {
		jdbcTemplate.update("DELETE FROM leads");
		transactionRepository.deleteAllInBatch();
		carRepository.deleteAllInBatch();
		clientRepository.deleteAllInBatch();
		partnerRepository.deleteAllInBatch();
	}

	/**
	 * K1: two sales of the same car to the same client. One succeeds and the other is refused as
	 * already sold; the purchase and the transaction count once (without the car lock: 2 sales, 2, 2).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k1TwoSalesOfTheSameCarSellItOnce() throws Exception {
		Client client = aClient("Ana");
		Car car = carRepository.saveAndFlush(aCar().build());
		SellCarRequest sale = new SellCarRequest(PRICE, client.getId());

		List<Throwable> outcomes = race(() -> carService.sell(car.getId(), sale), () -> carService.sell(car.getId(), sale));

		assertThat(outcomes).filteredOn(o -> o == null).hasSize(1);
		assertThat(outcomes).filteredOn(o -> o instanceof CarAlreadySoldException).hasSize(1);
		assertThat(purchasesCount(client)).isEqualTo(1);
		assertThat(transactionRepository.count()).isEqualTo(1);
	}

	/**
	 * K2: four sales of four consignment cars of the same partner (commission 100.25) to the same
	 * client. Every increment counts: {@code cars_count} 4, {@code total_commission} 401.00, {@code
	 * purchases_count} 4 (without the partner lock: 1 and 100.25).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k2FourConsignmentSalesOfTheSamePartnerAllCount() throws Exception {
		Partner partner = aPartner("Auto Silva");
		Client client = aClient("Ana");
		List<Callable<Object>> sales = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			Car car = carRepository.saveAndFlush(aConsignmentCar(partner, "100.25").build());
			sales.add(() -> carService.sell(car.getId(), new SellCarRequest(PRICE, client.getId())));
		}

		List<Throwable> outcomes = race(sales);

		assertThat(outcomes).containsOnlyNulls();
		Partner reloaded = partnerRepository.findById(partner.getId()).orElseThrow();
		assertThat(reloaded.getCarsCount()).isEqualTo(4);
		assertThat(reloaded.getTotalCommission()).isEqualByComparingTo("401.00");
		assertThat(purchasesCount(client)).isEqualTo(4);
	}

	/**
	 * K3: the reversal of car A and the sale of car B, same client, no consignment. The client ends
	 * with 1 purchase (without the client lock: 0).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k3AReversalAndASaleToTheSameClientBothCount() throws Exception {
		Client client = aClient("Ana");
		Car sold = carRepository.saveAndFlush(aCar().build());
		Car unsold = carRepository.saveAndFlush(aCar().build());
		carService.sell(sold.getId(), new SellCarRequest(PRICE, client.getId()));

		List<Throwable> outcomes = race(
				() -> carService.revertSale(sold.getId()),
				() -> carService.sell(unsold.getId(), new SellCarRequest(PRICE, client.getId())));

		assertThat(outcomes).containsOnlyNulls();
		assertThat(purchasesCount(client)).isEqualTo(1);
	}

	/**
	 * K4: a sale and a reservation of the same car. Both stick: {@code vendido} and {@code reservado}
	 * true (without the car lock in {@code reserve}: {@code vendido} false, the sale vanishes from the
	 * car while its counters and transaction stay).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k4ASaleAndAReservationOfTheSameCarBothStick() throws Exception {
		Car car = carRepository.saveAndFlush(aCar().build());

		List<Throwable> outcomes = race(
				() -> carService.sell(car.getId(), new SellCarRequest(PRICE, null)),
				() -> carService.reserve(car.getId()));

		assertThat(outcomes).containsOnlyNulls();
		Car reloaded = carRepository.findById(car.getId()).orElseThrow();
		assertThat(reloaded.isVendido()).isTrue();
		assertThat(reloaded.isReservado()).isTrue();
		assertThat(transactionRepository.count()).isEqualTo(1);
	}

	/**
	 * K5: a consignment sale and an edit of the partner's name. Both stick: {@code cars_count} 1 and
	 * the new name (without the lock in {@code PartnerService#update}: {@code cars_count} 0).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k5AConsignmentSaleAndAnEditOfThePartnerBothStick() throws Exception {
		Partner partner = aPartner("Auto Silva");
		Car car = carRepository.saveAndFlush(aConsignmentCar(partner, "500.00").build());

		List<Throwable> outcomes = race(
				() -> carService.sell(car.getId(), new SellCarRequest(PRICE, null)),
				() -> partnerService.update(partner.getId(), new PartnerRequest("Auto Silva Lda", null, null, null)));

		assertThat(outcomes).containsOnlyNulls();
		Partner reloaded = partnerRepository.findById(partner.getId()).orElseThrow();
		assertThat(reloaded.getCarsCount()).isEqualTo(1);
		assertThat(reloaded.getTotalCommission()).isEqualByComparingTo("500.00");
		assertThat(reloaded.getName()).isEqualTo("Auto Silva Lda");
	}

	/**
	 * K6: a sale and an edit of the buyer. Both stick: {@code purchases_count} 1 and the new name
	 * (without the lock in {@code ClientService#update}: 0).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k6ASaleAndAnEditOfTheBuyerBothStick() throws Exception {
		Client client = aClient("Ana");
		Car car = carRepository.saveAndFlush(aCar().build());

		List<Throwable> outcomes = race(
				() -> carService.sell(car.getId(), new SellCarRequest(PRICE, client.getId())),
				() -> clientService.update(client.getId(), new ClientRequest("Ana Silva", null, "912345678", null, null, null, null)));

		assertThat(outcomes).containsOnlyNulls();
		Client reloaded = clientRepository.findById(client.getId()).orElseThrow();
		assertThat(reloaded.getPurchasesCount()).isEqualTo(1);
		assertThat(reloaded.getName()).isEqualTo("Ana Silva");
	}

	/**
	 * K7: a sale to client C and the deletion of C. Exactly one wins, and the data stays coherent
	 * either way: C exists and the car is sold to C (the deletion gets 409), or C is gone and the car
	 * is unsold with no transaction (the sale gets 404). Which one wins is not asserted (without the
	 * lock in {@code ClientService#delete}: a sold car without client, and the client gone).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k7ASaleAndTheDeletionOfTheBuyerLeaveCoherentData() throws Exception {
		Client client = aClient("Ana");
		Car car = carRepository.saveAndFlush(aCar().build());

		List<Throwable> outcomes = race(
				() -> carService.sell(car.getId(), new SellCarRequest(PRICE, client.getId())),
				() -> {
					clientService.delete(client.getId());
					return null;
				});

		Car reloaded = carRepository.findById(car.getId()).orElseThrow();
		boolean clientExists = clientRepository.existsById(client.getId());
		if (clientExists) {
			assertThat(outcomes.get(0)).isNull();
			assertThat(outcomes.get(1)).isInstanceOf(ResourceInUseException.class);
			assertThat(reloaded.isVendido()).isTrue();
			assertThat(reloaded.getClient().getId()).isEqualTo(client.getId());
			assertThat(purchasesCount(client)).isEqualTo(1);
		} else {
			assertThat(outcomes.get(0)).isInstanceOf(ResourceNotFoundException.class);
			assertThat(outcomes.get(1)).isNull();
			assertThat(reloaded.isVendido()).isFalse();
			assertThat(reloaded.getClient()).isNull();
			assertThat(transactionRepository.count()).isZero();
		}
	}

	/**
	 * K8: a lead for a car whose {@code DELETE} has not committed yet. The lead waits for the commit
	 * and is saved without car, {@code origem} {@code website} (without {@code FOR KEY SHARE}: the
	 * {@code INSERT} fails on {@code leads_car_id_fkey} and the lead is lost).
	 *
	 * @throws Exception from the deleting connection or the worker
	 */
	@Test
	void k8ALeadForACarBeingDeletedIsSavedWithoutCar() throws Exception {
		Car car = carRepository.saveAndFlush(aCar().build());
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Connection deleting = dataSource.getConnection()) {
			deleting.setAutoCommit(false);
			try (PreparedStatement statement = deleting.prepareStatement("DELETE FROM cars WHERE id = ?")) {
				statement.setObject(1, car.getId());
				assertThat(statement.executeUpdate()).isEqualTo(1);
			}
			Future<LeadResponse> lead;
			try {
				lead = pool.submit(() -> leadService.createFromWebsite(
						new LeadRequest("Rui", "912345678", null, "Ainda esta disponivel?", car.getId(), "Audi", "A4")));
				awaitWaitingOnLocks(1);
			} finally {
				deleting.commit();
			}

			UUID leadId = lead.get(30, TimeUnit.SECONDS).id();
			assertThat(leadId).isNotNull();
			assertThat(jdbcTemplate.queryForObject("SELECT car_id FROM leads WHERE id = ?", UUID.class, leadId)).isNull();
			assertThat(jdbcTemplate.queryForObject("SELECT origem FROM leads WHERE id = ?", String.class, leadId))
					.isEqualTo("website");
		} finally {
			pool.shutdownNow();
		}
	}

	/**
	 * K9: two reversals of the same sale (the client has 2 purchases, this car and another). The
	 * second is the no-op: {@code purchases_count} 1 and only the other car's sale transaction left
	 * (without the car lock: 0).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k9TwoReversalsOfTheSameSaleUndoItOnce() throws Exception {
		Client client = aClient("Ana");
		Car reverted = carRepository.saveAndFlush(aCar().build());
		Car other = carRepository.saveAndFlush(aCar().build());
		carService.sell(reverted.getId(), new SellCarRequest(PRICE, client.getId()));
		carService.sell(other.getId(), new SellCarRequest(PRICE, client.getId()));

		List<Throwable> outcomes = race(() -> carService.revertSale(reverted.getId()), () -> carService.revertSale(reverted.getId()));

		assertThat(outcomes).containsOnlyNulls();
		assertThat(purchasesCount(client)).isEqualTo(1);
		assertThat(transactionRepository.findAll())
				.singleElement()
				.satisfies(transaction -> assertThat(transaction.getCar().getId()).isEqualTo(other.getId()));
	}

	/**
	 * K10: the {@code PUT} that moves a sold consignment car from partner A to partner B, and the
	 * sale of another consignment car of B. Both count on B: A ends with {@code cars_count} 0 and B
	 * with 2 (without the partner locks in the {@code PUT}: B with 1).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k10MovingASoldCarToAPartnerAndSellingAnotherCarOfThatPartnerBothCount() throws Exception {
		Partner partnerA = aPartner("Parceiro A");
		Partner partnerB = aPartner("Parceiro B");
		Car moved = carRepository.saveAndFlush(aConsignmentCar(partnerA, "500.00").build());
		Car sold = carRepository.saveAndFlush(aConsignmentCar(partnerB, "300.00").build());
		carService.sell(moved.getId(), new SellCarRequest(PRICE, null));
		CarRequest toPartnerB = aConsignmentEdit(partnerB, "500.00");

		List<Throwable> outcomes = race(
				() -> carService.update(moved.getId(), toPartnerB),
				() -> carService.sell(sold.getId(), new SellCarRequest(PRICE, null)));

		assertThat(outcomes).containsOnlyNulls();
		Partner reloadedA = partnerRepository.findById(partnerA.getId()).orElseThrow();
		Partner reloadedB = partnerRepository.findById(partnerB.getId()).orElseThrow();
		assertThat(reloadedA.getCarsCount()).isZero();
		assertThat(reloadedA.getTotalCommission()).isEqualByComparingTo("0.00");
		assertThat(reloadedB.getCarsCount()).isEqualTo(2);
		assertThat(reloadedB.getTotalCommission()).isEqualByComparingTo("800.00");
	}

	/**
	 * K11: a {@code PUT} and a sale of the same car. Both stick, whichever goes first: {@code vendido}
	 * true, the sale price and the new model (without the car lock in {@code update}: the {@code PUT}
	 * writes back the row it read before the sale, {@code vendido} false, while the counters and the
	 * transaction stay; or the sale writes back the old model).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k11AnEditAndASaleOfTheSameCarBothStick() throws Exception {
		Car car = carRepository.saveAndFlush(aCar().build());

		List<Throwable> outcomes = race(
				() -> carService.update(car.getId(), anEdit("A6")),
				() -> carService.sell(car.getId(), new SellCarRequest(PRICE, null)));

		assertThat(outcomes).containsOnlyNulls();
		Car reloaded = carRepository.findById(car.getId()).orElseThrow();
		assertThat(reloaded.isVendido()).isTrue();
		assertThat(reloaded.getPrecoVenda()).isEqualByComparingTo(PRICE);
		assertThat(reloaded.getModelo()).isEqualTo("A6");
		assertThat(transactionRepository.count()).isEqualTo(1);
	}

	/**
	 * K12: the {@code DELETE} (with its transactions, the default) and the sale to client C of the
	 * same car. Whichever goes first, the car is gone with no transaction left and C with no purchase:
	 * either the deletion undoes the committed sale, or the sale finds no car (404) (without the car
	 * lock in {@code delete}: the deletion decides on the unsold car it read, the sale commits, and a
	 * sale transaction is left without car while C keeps 1 purchase of a car that no longer exists).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k12TheDeletionAndTheSaleOfTheSameCarLeaveNoOrphanSale() throws Exception {
		Client client = aClient("Ana");
		Car car = carRepository.saveAndFlush(aCar().build());

		List<Throwable> outcomes = race(
				() -> {
					carService.delete(car.getId(), true);
					return null;
				},
				() -> carService.sell(car.getId(), new SellCarRequest(PRICE, client.getId())));

		assertThat(outcomes.get(0)).isNull();
		assertThat(outcomes.get(1)).satisfiesAnyOf(
				sale -> assertThat(sale).isNull(),
				sale -> assertThat(sale).isInstanceOf(ResourceNotFoundException.class));
		assertThat(carRepository.existsById(car.getId())).isFalse();
		assertThat(transactionRepository.count()).isZero();
		assertThat(purchasesCount(client)).isZero();
	}

	/**
	 * K13: the release of a reservation and the sale of the same reserved car. Both stick: {@code
	 * vendido} true and {@code reservado} false (without the car lock in {@code releaseReservation}:
	 * {@code vendido} false, as K4 for {@code reserve}).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k13AReleaseOfTheReservationAndASaleOfTheSameCarBothStick() throws Exception {
		Car car = carRepository.saveAndFlush(aCar().reservado(true).build());

		List<Throwable> outcomes = race(
				() -> carService.releaseReservation(car.getId()),
				() -> carService.sell(car.getId(), new SellCarRequest(PRICE, null)));

		assertThat(outcomes).containsOnlyNulls();
		Car reloaded = carRepository.findById(car.getId()).orElseThrow();
		assertThat(reloaded.isVendido()).isTrue();
		assertThat(reloaded.isReservado()).isFalse();
		assertThat(transactionRepository.count()).isEqualTo(1);
	}

	/**
	 * K14: the reversal of consignment car R (partner P, commission 100.00, sold) and the sale of
	 * consignment car S (P, 200.00). P ends with {@code cars_count} 1 and {@code total_commission}
	 * 200.00 (without the partner lock in {@code revertSale}: 0 and 0.00, or 2 and 300.00).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k14AConsignmentReversalAndASaleOfTheSamePartnerBothCount() throws Exception {
		Partner partner = aPartner("Auto Silva");
		Car reverted = carRepository.saveAndFlush(aConsignmentCar(partner, "100.00").build());
		Car sold = carRepository.saveAndFlush(aConsignmentCar(partner, "200.00").build());
		carService.sell(reverted.getId(), new SellCarRequest(PRICE, null));

		List<Throwable> outcomes = race(
				() -> carService.revertSale(reverted.getId()),
				() -> carService.sell(sold.getId(), new SellCarRequest(PRICE, null)));

		assertThat(outcomes).containsOnlyNulls();
		Partner reloaded = partnerRepository.findById(partner.getId()).orElseThrow();
		assertThat(reloaded.getCarsCount()).isEqualTo(1);
		assertThat(reloaded.getTotalCommission()).isEqualByComparingTo("200.00");
	}

	/**
	 * K15: the {@code DELETE} of car D, sold to client C, and the sale of car S to C. C ends with 1
	 * purchase, S's, and only S's transaction is left (without the client lock in {@code delete}: 0 or
	 * 2).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void k15TheDeletionOfASoldCarAndASaleToTheSameClientBothCount() throws Exception {
		Client client = aClient("Ana");
		Car deleted = carRepository.saveAndFlush(aCar().build());
		Car sold = carRepository.saveAndFlush(aCar().build());
		carService.sell(deleted.getId(), new SellCarRequest(PRICE, client.getId()));

		List<Throwable> outcomes = race(
				() -> {
					carService.delete(deleted.getId(), true);
					return null;
				},
				() -> carService.sell(sold.getId(), new SellCarRequest(PRICE, client.getId())));

		assertThat(outcomes).containsOnlyNulls();
		assertThat(purchasesCount(client)).isEqualTo(1);
		assertThat(transactionRepository.findAll())
				.singleElement()
				.satisfies(transaction -> assertThat(transaction.getCar().getId()).isEqualTo(sold.getId()));
	}

	/**
	 * The lock order of AC C.1.d: a {@code PUT} that locks two partners takes the lower id first,
	 * whatever the request's order. A sold consignment car moves from partner {@code high} to partner
	 * {@code low} while another connection holds {@code low}: the {@code PUT} has to be waiting on
	 * {@code low} while {@code high} is still free (in the request's order it would hold {@code high}
	 * and wait on {@code low}, and a {@code PUT} moving another car the other way would deadlock with
	 * it, {@code 40P01}). Probed with {@code NOWAIT}, so the result does not depend on timing.
	 *
	 * @throws Exception from the holding or probing connections, or the worker
	 */
	@Test
	void aPutLockingTwoPartnersTakesTheLowerIdFirst() throws Exception {
		List<Partner> partners = new ArrayList<>(List.of(aPartner("Parceiro 1"), aPartner("Parceiro 2")));
		partners.sort(Comparator.comparing(partner -> partner.getId().toString()));
		Partner low = partners.get(0);
		Partner high = partners.get(1);
		Car moved = carRepository.saveAndFlush(aConsignmentCar(high, "100.00").build());
		carService.sell(moved.getId(), new SellCarRequest(PRICE, null));

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Connection holding = dataSource.getConnection(); Connection probing = dataSource.getConnection()) {
			holding.setAutoCommit(false);
			lockPartnerRow(holding, low, "");
			Future<?> edit;
			boolean highFreeWhileWaitingOnLow;
			try {
				edit = pool.submit(() -> carService.update(moved.getId(), aConsignmentEdit(low, "100.00")));
				awaitWaitingOnLocks(1);
				highFreeWhileWaitingOnLow = lockPartnerRow(probing, high, " NOWAIT");
			} finally {
				holding.commit();
			}

			edit.get(30, TimeUnit.SECONDS);
			assertThat(highFreeWhileWaitingOnLow).as("partner with the higher id still free").isTrue();
			assertThat(partnerRepository.findById(high.getId()).orElseThrow().getCarsCount()).isZero();
			assertThat(partnerRepository.findById(low.getId()).orElseThrow().getCarsCount()).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	/**
	 * Locks a partner's row as {@code findByIdForUpdate} does ({@code FOR NO KEY UPDATE}) on the
	 * given connection; with auto-commit on, the lock is released at once.
	 *
	 * @return {@code true} if the row was locked, {@code false} if {@code NOWAIT} found it locked
	 *     ({@code 55P03})
	 */
	private static boolean lockPartnerRow(Connection connection, Partner partner, String nowait) throws SQLException {
		try (PreparedStatement statement =
				connection.prepareStatement("SELECT id FROM partners WHERE id = ? FOR NO KEY UPDATE" + nowait)) {
			statement.setObject(1, partner.getId());
			try (ResultSet row = statement.executeQuery()) {
				return row.next();
			}
		} catch (SQLException failed) {
			if ("55P03".equals(failed.getSQLState())) {
				return false;
			}
			throw failed;
		}
	}

	/**
	 * The car lock before the highlight advisory lock (AC C.1.d) changes the edge case TASK-003
	 * documented: two requests featuring the same car with 7 others featured. The second waits for
	 * the first, then sees the car already featured and succeeds without asking for a slot (before,
	 * it counted 8 and got 409 although the car ended up featured).
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void featuringTheSameCarTwiceAtTheLimitSucceedsTwiceAndFeaturesItOnce() throws Exception {
		for (int i = 0; i < 7; i++) {
			carRepository.saveAndFlush(aCar().destaque(true).build());
		}
		Car car = carRepository.saveAndFlush(aCar().build());

		List<Throwable> outcomes = race(
				() -> carService.highlight(car.getId(), new HighlightRequest(true)),
				() -> carService.highlight(car.getId(), new HighlightRequest(true)));

		assertThat(outcomes).containsOnlyNulls();
		assertThat(carRepository.countByDestaqueTrue()).isEqualTo(8);
	}

	/**
	 * Runs the requests concurrently behind the {@code SHARE} gate on {@code cars}, {@code clients}
	 * and {@code partners}, and releases them only once all are waiting on a lock.
	 *
	 * @param requests the service calls to race
	 * @return per request, in order, {@code null} if it succeeded or the exception it threw
	 * @throws Exception if a request never reaches a lock wait, or does not finish in time
	 */
	private List<Throwable> race(List<Callable<Object>> requests) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(requests.size());
		try (Connection gate = dataSource.getConnection()) {
			gate.setAutoCommit(false);
			try (Statement statement = gate.createStatement()) {
				statement.execute("LOCK TABLE cars, clients, partners IN SHARE MODE");
			}
			List<Future<Object>> results = new ArrayList<>();
			try {
				for (Callable<Object> request : requests) {
					results.add(pool.submit(request));
				}
				awaitWaitingOnLocks(requests.size());
			} finally {
				gate.commit();
			}
			List<Throwable> outcomes = new ArrayList<>();
			for (Future<Object> result : results) {
				try {
					result.get(30, TimeUnit.SECONDS);
					outcomes.add(null);
				} catch (ExecutionException failed) {
					outcomes.add(failed.getCause());
				}
			}
			return outcomes;
		} finally {
			pool.shutdownNow();
		}
	}

	@SafeVarargs
	private List<Throwable> race(Callable<Object>... requests) throws Exception {
		return race(List.of(requests));
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

	private int purchasesCount(Client client) {
		return clientRepository.findById(client.getId()).orElseThrow().getPurchasesCount();
	}

	private Client aClient(String name) {
		return clientRepository.saveAndFlush(Client.builder().name(name).phone("912345678").build());
	}

	private Partner aPartner(String name) {
		return partnerRepository.saveAndFlush(Partner.builder().name(name).build());
	}

	/** An edit of an {@link #aCar()} that only changes the model, without consignment. */
	private static CarRequest anEdit(String modelo) {
		return new CarRequest(
				"Audi", modelo, 2019, new BigDecimal("22000.00"), 80000, "Branco", "Gasolina", "Manual", "Nacional",
				null, null, null, null, null, null, null, null, null, null, null);
	}

	/** An edit of an {@link #aCar()} that puts it in consignment with this partner and commission. */
	private static CarRequest aConsignmentEdit(Partner partner, String commission) {
		return new CarRequest(
				"Audi", "A4", 2019, new BigDecimal("22000.00"), 80000, "Branco", "Gasolina", "Manual", "Nacional",
				null, null, null, null, true, partner.getId(), new BigDecimal(commission), 0, null, null, null);
	}

	private static Car.CarBuilder aConsignmentCar(Partner partner, String commission) {
		return aCar().consignacao(true).partner(partner).commissionValue(new BigDecimal(commission));
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
