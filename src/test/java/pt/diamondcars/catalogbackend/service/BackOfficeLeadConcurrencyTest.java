package pt.diamondcars.catalogbackend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.lead.LeadOrigin;
import pt.diamondcars.catalogbackend.domain.lead.LeadRepository;
import pt.diamondcars.catalogbackend.domain.lead.LeadStatus;
import pt.diamondcars.catalogbackend.domain.notification.Notification;
import pt.diamondcars.catalogbackend.domain.notification.NotificationRepository;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import pt.diamondcars.catalogbackend.web.dto.BackOfficeLeadRequest;
import pt.diamondcars.catalogbackend.web.dto.BackOfficeLeadResponse;
import pt.diamondcars.catalogbackend.web.exception.ResourceNotFoundException;

/**
 * Proves, deterministically and against real PostgreSQL, the concurrency rules of the back-office
 * lead and notification writes (TASK-006, AC E.2, E.3, E.5). Technique of {@code web/CLAUDE.md}
 * ("Testar corridas", K8 variant): the gate is a separate connection that deleted a row without
 * committing; the request runs in a thread against the service, the test waits until {@code
 * pg_stat_activity} shows it waiting on a lock, commits the gate, and asserts the outcome.
 *
 * <p>Shares the cached context of the other plain {@code @SpringBootTest} classes ({@code
 * PartnerDeleteConcurrencyTest}): one worker, the gate and the polling query fit the default pool.
 */
@SpringBootTest
class BackOfficeLeadConcurrencyTest extends AbstractPostgresIntegrationTest {

	private static final Duration WAITING_DEADLINE = Duration.ofSeconds(20);

	@Autowired private BackOfficeLeadService leadService;
	@Autowired private NotificationService notificationService;
	@Autowired private LeadRepository leadRepository;
	@Autowired private CarRepository carRepository;
	@Autowired private NotificationRepository notificationRepository;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private DataSource dataSource;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void cleanDatabase() {
		jdbcTemplate.update("DELETE FROM leads");
		carRepository.deleteAllInBatch();
	}

	/**
	 * AC E.5 (a): a {@code PUT} of a lead whose {@code DELETE} has not committed waits for it and then
	 * answers 404, writing nothing. Without {@code findByIdForUpdate} the {@code PUT} read the lead,
	 * its {@code UPDATE} touched 0 rows after the commit, and Hibernate's {@code StaleStateException}
	 * reached the caller as a 500.
	 *
	 * @throws Exception from the deleting connection or the worker
	 */
	@Test
	void anEditOfALeadBeingDeletedIsA404AndWritesNothing() throws Exception {
		Lead lead = aLead(null);

		Throwable outcome = whileUncommitted("DELETE FROM leads WHERE id = ?", lead.getId(),
				() -> leadService.update(lead.getId(), anEdit("Notas novas")));

		assertThat(outcome)
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage("Lead nao encontrado: " + lead.getId());
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM leads", Long.class)).isZero();
	}

	/**
	 * Two deletions of the same lead: the second waits for the first and answers 404 (without the
	 * lock: 0 rows deleted at flush, {@code StaleStateException}, 500).
	 *
	 * @throws Exception from the deleting connection or the worker
	 */
	@Test
	void aSecondDeletionOfTheSameLeadIsA404() throws Exception {
		Lead lead = aLead(null);

		Throwable outcome = whileUncommitted("DELETE FROM leads WHERE id = ?", lead.getId(), () -> {
			leadService.delete(lead.getId());
			return null;
		});

		assertThat(outcome).isInstanceOf(ResourceNotFoundException.class);
	}

	/**
	 * AC E.5 (b): the car of a lead deleted concurrently with an edit of the lead. The deletion's
	 * {@code ON DELETE SET NULL} holds the lead's row, so the edit waits for it, then reads the lead
	 * without car and saves the notes: 200, {@code notas} saved, {@code car_id} null, the car gone.
	 *
	 * <p>The literal order of the AC (deletion committed between the {@code findByIdForUpdate} and the
	 * flush of the edit) cannot happen: while the edit holds the lead's row, the deletion's {@code SET
	 * NULL} waits for it. What protects that order is {@code updatable = false} on {@code Lead.car},
	 * proven without the lock by {@link #anEditNeverWritesBackTheCarOfALead()}.
	 *
	 * @throws Exception from the deleting connection or the worker
	 */
	@Test
	void anEditOfALeadWhoseCarIsBeingDeletedSavesTheNotesAndEndsWithoutCar() throws Exception {
		Car car = carRepository.saveAndFlush(aCar());
		Lead lead = aLead(car);

		Throwable outcome = whileUncommitted("DELETE FROM cars WHERE id = ?", car.getId(), () -> {
			BackOfficeLeadResponse response = leadService.update(lead.getId(), anEdit("Ligar amanha"));
			assertThat(response.carroId()).isNull();
			return response;
		});

		assertThat(outcome).isNull();
		assertThat(jdbcTemplate.queryForMap("SELECT notas, car_id FROM leads WHERE id = ?", lead.getId()))
				.containsEntry("notas", "Ligar amanha")
				.containsEntry("car_id", null);
		assertThat(carRepository.existsById(car.getId())).isFalse();
	}

	/**
	 * {@code Lead.car} is {@code updatable = false} (AC E.2): an edit flushed after the car was
	 * deleted and its {@code SET NULL} committed (here: a read without the lock, the situation the
	 * lock otherwise prevents) does not write the deleted car back. Without it the whole-row {@code
	 * UPDATE} puts {@code car_id} back, fails on {@code leads_car_id_fkey} (a 409 for the caller) and the
	 * edit is lost.
	 */
	@Test
	void anEditNeverWritesBackTheCarOfALead() {
		Car car = carRepository.saveAndFlush(aCar());
		Lead lead = aLead(car);

		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			Lead stale = leadRepository.findById(lead.getId()).orElseThrow();
			assertThat(stale.getCar().getId()).isEqualTo(car.getId());
			deleteCommitted(car.getId());
			stale.setNotas("Editado depois de o carro sair");
			leadRepository.flush();
		});

		assertThat(jdbcTemplate.queryForMap("SELECT notas, car_id FROM leads WHERE id = ?", lead.getId()))
				.containsEntry("notas", "Editado depois de o carro sair")
				.containsEntry("car_id", null);
	}

	/**
	 * AC E.3: marking as read a notification whose lead is being deleted (the {@code ON DELETE
	 * CASCADE} holds the notification's row) waits and answers 404. Load, modify and save ({@code
	 * dcbo-backend}) read the notification, updated 0 rows after the commit and answered 500.
	 *
	 * @throws Exception from the deleting connection or the worker
	 */
	@Test
	void markingAsReadANotificationWhoseLeadIsBeingDeletedIsA404() throws Exception {
		Lead lead = aLead(null);
		Notification notification = aNotification(lead);

		Throwable outcome = whileUncommitted("DELETE FROM leads WHERE id = ?", lead.getId(),
				() -> notificationService.markAsRead(notification.getId()));

		assertThat(outcome)
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage("Notificacao nao encontrada: " + notification.getId());
	}

	/**
	 * AC E.3: deleting a notification whose lead is being deleted waits and answers 404 (load and
	 * delete: 0 rows at flush, 500).
	 *
	 * @throws Exception from the deleting connection or the worker
	 */
	@Test
	void deletingANotificationWhoseLeadIsBeingDeletedIsA404() throws Exception {
		Lead lead = aLead(null);
		Notification notification = aNotification(lead);

		Throwable outcome = whileUncommitted("DELETE FROM leads WHERE id = ?", lead.getId(), () -> {
			notificationService.delete(notification.getId());
			return null;
		});

		assertThat(outcome).isInstanceOf(ResourceNotFoundException.class);
	}

	/**
	 * Runs {@code sql} with {@code id} on a separate connection without committing, starts {@code
	 * worker} in a thread, waits until it waits on a lock, commits, and returns the worker's outcome.
	 *
	 * @return {@code null} if the worker succeeded, or the exception it threw
	 */
	private Throwable whileUncommitted(String sql, UUID id, Callable<?> worker) throws Exception {
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try (Connection gate = dataSource.getConnection()) {
			gate.setAutoCommit(false);
			Future<?> task;
			try {
				try (PreparedStatement statement = gate.prepareStatement(sql)) {
					statement.setObject(1, id);
					assertThat(statement.executeUpdate()).isEqualTo(1);
				}
				task = pool.submit(worker);
				awaitOneSessionWaitingOnALock(task);
			} finally {
				gate.commit();
			}
			try {
				task.get(30, TimeUnit.SECONDS);
				return null;
			} catch (ExecutionException failed) {
				return failed.getCause();
			}
		} finally {
			pool.shutdownNow();
		}
	}

	/** Deletes a car in its own, committed transaction (autocommit). */
	private void deleteCommitted(UUID carId) {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("DELETE FROM cars WHERE id = ?")) {
			connection.setAutoCommit(true);
			statement.setObject(1, carId);
			assertThat(statement.executeUpdate()).isEqualTo(1);
		} catch (SQLException failure) {
			throw new IllegalStateException(failure);
		}
	}

	/**
	 * Polls {@code pg_stat_activity} until one session waits on a lock. Fails fast if the worker
	 * finished first: it did not wait for the gate, so the test would not prove the race.
	 */
	private void awaitOneSessionWaitingOnALock(Future<?> worker) throws InterruptedException {
		Instant deadline = Instant.now().plus(WAITING_DEADLINE);
		while (Instant.now().isBefore(deadline)) {
			assertThat(worker.isDone()).as("the worker finished without waiting for the gate").isFalse();
			Integer waiting = jdbcTemplate.queryForObject(
					"SELECT count(*)::int FROM pg_stat_activity WHERE datname = current_database()"
							+ " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'",
					Integer.class);
			if (waiting != null && waiting == 1) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("The worker never waited on a lock");
	}

	private Lead aLead(Car car) {
		return leadRepository.saveAndFlush(Lead.builder()
				.nome("Ana")
				.telefone("912345678")
				.car(car)
				.carroMarca(car != null ? "Audi" : null)
				.status(LeadStatus.ATIVO)
				.origem(car != null ? LeadOrigin.WEBSITE : LeadOrigin.WEBSITE_CONTACTO)
				.build());
	}

	private Notification aNotification(Lead lead) {
		return notificationRepository.saveAndFlush(Notification.builder()
				.tipo("novo_lead")
				.titulo("Novo Lead")
				.mensagem("Novo lead de Ana")
				.prioridade("media")
				.lead(lead)
				.build());
	}

	private static BackOfficeLeadRequest anEdit(String notas) {
		return new BackOfficeLeadRequest("Ana", "912345678", null, notas, "contactado", null, null, null, null, null);
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
}
