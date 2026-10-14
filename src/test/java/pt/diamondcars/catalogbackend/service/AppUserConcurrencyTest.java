package pt.diamondcars.catalogbackend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import pt.diamondcars.catalogbackend.domain.user.AppUser;
import pt.diamondcars.catalogbackend.domain.user.AppUserRepository;
import pt.diamondcars.catalogbackend.domain.user.AppUserRole;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import pt.diamondcars.catalogbackend.web.dto.AppUserActiveRequest;
import pt.diamondcars.catalogbackend.web.dto.AppUserRequest;
import pt.diamondcars.catalogbackend.web.dto.AppUserUpdateRequest;
import pt.diamondcars.catalogbackend.web.exception.ResourceNotFoundException;

/**
 * Proves, deterministically and against real PostgreSQL, the concurrency rules of the profile
 * writes (TASK-007, AC C.6 and the ASSUMPTION of C.2; K-U1 to K-U4 of AC F.3, plus K-U5 for {@code
 * setActive}; K-U6, TASK-020, for the strength of the lock). Technique of {@code
 * web/CLAUDE.md} ("Testar corridas", K8 variant): the gate is a separate connection that wrote the
 * profile's row without committing (K-U6: that took {@code FOR SHARE} on it); the service calls run in
 * threads, the test waits until {@code pg_stat_activity} shows all of them waiting on a lock, commits
 * the gate and asserts the outcome.
 *
 * <p>The worker threads have no security context, so the self-deactivation and self-deletion checks
 * never fire here. Shares the cached context of the other plain {@code @SpringBootTest} classes
 * ({@code BackOfficeLeadConcurrencyTest}): two workers at most, the gate and the polling query (4
 * connections) fit the default pool.
 */
@SpringBootTest
class AppUserConcurrencyTest extends AbstractPostgresIntegrationTest {

	private static final Duration WAITING_DEADLINE = Duration.ofSeconds(20);

	@Autowired private AppUserService appUserService;
	@Autowired private AppUserRepository appUserRepository;
	@Autowired private DataSource dataSource;
	@Autowired private JdbcTemplate jdbcTemplate;

	@BeforeEach
	@AfterEach
	void cleanDatabase() {
		jdbcTemplate.update("DELETE FROM app_users");
	}

	/**
	 * K-U1: a name edit concurrent with another admin's deactivation of the same user keeps the
	 * deactivation. With the lock the edit waits, reads {@code active = false} and writes it back.
	 * Without it (mutation 1 of AC F.5) the edit read {@code active = true} before the deactivation
	 * committed, its whole-row {@code UPDATE} waited for the row and then wrote {@code true} back: the
	 * deactivated user got their access back.
	 *
	 * @throws Exception from the gate connection or the worker
	 */
	@Test
	void aDeactivationIsNeverUndoneByAConcurrentProfileEdit() throws Exception {
		AppUser user = aProfile();

		Throwable outcome = whileUncommitted("UPDATE app_users SET active = false WHERE id = ?", user.getId(),
				() -> appUserService.update(user.getId(), new AppUserUpdateRequest("Nome Novo", "admin")));

		assertThat(outcome).isNull();
		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT active, name, role FROM app_users WHERE id = ?", user.getId());
		assertThat(row)
				.containsEntry("active", false)
				.containsEntry("name", "Nome Novo")
				.containsEntry("role", "admin");
	}

	/**
	 * K-U5 (beyond AC F.3, which has no race for {@code setActive}): the mirror of K-U1, a
	 * deactivation concurrent with another admin's name edit keeps the edit. Without the lock in
	 * {@code setActive} the deactivation read the old name and wrote it back.
	 *
	 * @throws Exception from the gate connection or the worker
	 */
	@Test
	void anEditIsNeverUndoneByAConcurrentDeactivation() throws Exception {
		AppUser user = aProfile();

		Throwable outcome = whileUncommitted("UPDATE app_users SET name = 'Nome Novo' WHERE id = ?", user.getId(),
				() -> appUserService.setActive(user.getId(), new AppUserActiveRequest(false)));

		assertThat(outcome).isNull();
		assertThat(jdbcTemplate.queryForMap("SELECT active, name FROM app_users WHERE id = ?", user.getId()))
				.containsEntry("active", false)
				.containsEntry("name", "Nome Novo");
	}

	/**
	 * K-U2: an edit of a profile whose deletion has not committed waits for it, then answers 404 and
	 * writes nothing. Without the lock the edit read the row, its {@code UPDATE} touched 0 rows after
	 * the commit and {@code StaleStateException} reached the caller as a 500.
	 *
	 * @throws Exception from the gate connection or the worker
	 */
	@Test
	void anEditOfAUserBeingDeletedIsA404AndWritesNothing() throws Exception {
		AppUser user = aProfile();

		Throwable outcome = whileUncommitted("DELETE FROM app_users WHERE id = ?", user.getId(),
				() -> appUserService.update(user.getId(), new AppUserUpdateRequest("Nome Novo", "admin")));

		assertThat(outcome)
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage("Utilizador nao encontrado: " + user.getId());
		assertThat(appUserRepository.count()).isZero();
	}

	/**
	 * K-U3: two deletions of the same profile; the second waits for the first and answers 404 (without
	 * the lock, mutation 2 of AC F.5: 0 rows deleted at flush, a 500).
	 *
	 * @throws Exception from the gate connection or the worker
	 */
	@Test
	void aSecondDeletionOfTheSameUserIsA404() throws Exception {
		AppUser user = aProfile();

		Throwable outcome = whileUncommitted("DELETE FROM app_users WHERE id = ?", user.getId(), () -> {
			appUserService.delete(user.getId());
			return null;
		});

		assertThat(outcome)
				.isInstanceOf(ResourceNotFoundException.class)
				.hasMessage("Utilizador nao encontrado: " + user.getId());
	}

	/**
	 * K-U4 (ASSUMPTION of AC C.2): two creations with the same {@code authSubject}. The second passes
	 * the lookup (the first is not committed), its {@code INSERT} waits on the unique index and then
	 * fails with {@link DataIntegrityViolationException}, which the shared handler answers with 409
	 * ({@code ApiExceptionHandlerTest}), never a 500. One row, the first one.
	 *
	 * @throws Exception from the gate connection or the worker
	 */
	@Test
	void twoCreationsWithTheSameAuthSubjectEndInOneRowAndAConflict() throws Exception {
		UUID firstId = UUID.randomUUID();
		String insert = "INSERT INTO app_users (id, auth_subject, email, name, role)"
				+ " VALUES (?, 'auth0|twice', 'primeiro@diamondcars.pt', 'Primeiro', 'user')";

		Throwable outcome = whileUncommitted(insert, firstId,
				() -> appUserService.create(new AppUserRequest("auth0|twice", "segundo@diamondcars.pt", "Segundo", "admin")));

		assertThat(outcome)
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("idx_app_users_auth_subject");
		assertThat(appUserRepository.findAll())
				.singleElement()
				.satisfies(user -> {
					assertThat(user.getId()).isEqualTo(firstId);
					assertThat(user.getName()).isEqualTo("Primeiro");
				});
	}

	/**
	 * K-U6 (TASK-020 C.1): two concurrent edits of the same profile both succeed and leave one of the
	 * two edits whole. Proves the strength of {@code AppUserRepository#findByIdForUpdate}, which no
	 * other test here does: the gates of K-U1, K-U2, K-U3 and K-U5 write the row, which blocks {@code FOR SHARE} as
	 * much as {@code FOR NO KEY UPDATE}, so those tests also pass with {@code PESSIMISTIC_READ}.
	 *
	 * <p>Behind a {@code FOR SHARE} gate, with {@code PESSIMISTIC_WRITE} ({@code FOR NO KEY UPDATE})
	 * both edits wait in the lock's {@code SELECT} and then run one after the other. With {@code
	 * PESSIMISTIC_READ} (mutant X1) both take the shared lock, both wait in their {@code UPDATE}, and
	 * once the gate commits each waits for the other: one fails with {@code CannotAcquireLockException}
	 * ({@code deadlock detected}, 40P01) after {@code deadlock_timeout}, a 500 for the caller. One
	 * worker is not enough: the gate's {@code FOR SHARE} also blocks the mutant's {@code UPDATE}, so a
	 * single edit waits on a lock in both modes.
	 *
	 * @throws Exception from the gate connection or the workers
	 */
	@Test
	void twoConcurrentEditsOfTheSameUserBothSucceed() throws Exception {
		AppUser user = aProfile();

		List<Throwable> outcomes = whileShareLocked("app_users", user.getId(),
				() -> appUserService.update(user.getId(), new AppUserUpdateRequest("Nome Um", "admin")),
				() -> appUserService.update(user.getId(), new AppUserUpdateRequest("Nome Dois", "user")));

		assertThat(outcomes).containsOnlyNulls();
		Map<String, Object> row =
				jdbcTemplate.queryForMap("SELECT name, role FROM app_users WHERE id = ?", user.getId());
		assertThat(List.of(row.get("name"), row.get("role")))
				.isIn(List.of("Nome Um", "admin"), List.of("Nome Dois", "user"));
	}

	/**
	 * Runs {@code sql} with {@code id} on a separate connection without committing, starts {@code
	 * worker} in a thread, waits until it waits on a lock, commits, and returns the worker's outcome.
	 * A worker that does not answer within 30 s fails the test ({@code TimeoutException}), never
	 * counts as a success.
	 *
	 * @return {@code null} if the worker succeeded, or the exception it threw
	 */
	private Throwable whileUncommitted(String sql, UUID id, Callable<?> worker) throws Exception {
		return behindGate(gate -> {
			try (PreparedStatement statement = gate.prepareStatement(sql)) {
				statement.setObject(1, id);
				assertThat(statement.executeUpdate()).isEqualTo(1);
			}
		}, worker).getFirst();
	}

	/**
	 * Takes {@code FOR SHARE} on the row {@code id} of {@code table} on a separate connection, starts
	 * every worker in its own thread, waits until all of them wait on a lock, commits, and returns
	 * their outcomes. The gate is a query: {@code FOR SHARE} writes nothing.
	 *
	 * @return per worker, in order, {@code null} if it succeeded or the exception it threw
	 */
	private List<Throwable> whileShareLocked(String table, UUID id, Callable<?>... workers) throws Exception {
		return behindGate(gate -> {
			try (PreparedStatement statement =
					gate.prepareStatement("SELECT 1 FROM " + table + " WHERE id = ? FOR SHARE")) {
				statement.setObject(1, id);
				try (ResultSet locked = statement.executeQuery()) {
					assertThat(locked.next()).as("row %s of %s", id, table).isTrue();
				}
			}
		}, workers);
	}

	/**
	 * Opens the gate (a statement left uncommitted on its own connection), starts the workers, waits
	 * until all of them wait on a lock, commits the gate, and collects the outcomes. A worker that does
	 * not answer within 30 s fails the test ({@code TimeoutException}), never counts as a success.
	 */
	private List<Throwable> behindGate(GateStatement gateStatement, Callable<?>... workers) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(workers.length);
		try (Connection gate = dataSource.getConnection()) {
			gate.setAutoCommit(false);
			List<Future<?>> tasks = new ArrayList<>();
			try {
				gateStatement.runOn(gate);
				for (Callable<?> worker : workers) {
					tasks.add(pool.submit(worker));
				}
				awaitSessionsWaitingOnALock(workers.length, tasks);
			} finally {
				gate.commit();
			}
			List<Throwable> outcomes = new ArrayList<>();
			for (Future<?> task : tasks) {
				outcomes.add(outcomeOf(task));
			}
			return outcomes;
		} finally {
			pool.shutdownNow();
		}
	}

	private static Throwable outcomeOf(Future<?> task) throws InterruptedException, TimeoutException {
		try {
			task.get(30, TimeUnit.SECONDS);
			return null;
		} catch (ExecutionException failed) {
			return failed.getCause();
		}
	}

	/**
	 * Polls {@code pg_stat_activity} until {@code expected} sessions wait on a lock. Fails fast if a
	 * worker finished first: it did not wait for the gate, so the test would not prove the race.
	 */
	private void awaitSessionsWaitingOnALock(int expected, List<Future<?>> workers) throws InterruptedException {
		Instant deadline = Instant.now().plus(WAITING_DEADLINE);
		while (Instant.now().isBefore(deadline)) {
			assertThat(workers).as("a worker finished without waiting for the gate").noneMatch(Future::isDone);
			Integer waiting = jdbcTemplate.queryForObject(
					"SELECT count(*)::int FROM pg_stat_activity WHERE datname = current_database()"
							+ " AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'",
					Integer.class);
			if (waiting != null && waiting == expected) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("Never saw " + expected + " session(s) waiting on a lock");
	}

	/** The statement that opens a gate, run on the gate's connection before any worker starts. */
	@FunctionalInterface
	private interface GateStatement {
		void runOn(Connection gate) throws SQLException;
	}

	private AppUser aProfile() {
		return appUserRepository.saveAndFlush(AppUser.builder()
				.authSubject("auth0|" + UUID.randomUUID())
				.email("utilizador@diamondcars.pt")
				.name("Nome Antigo")
				.role(AppUserRole.USER)
				.build());
	}
}
