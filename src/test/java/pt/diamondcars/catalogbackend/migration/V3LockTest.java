package pt.diamondcars.catalogbackend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * Proves the three lines at the top of {@code V3__drop_lead_forwarding.sql} (TASK-006, AC B.1 and
 * B.5): the migration that runs while the old instance still serves traffic during a Render deploy
 * fails cleanly instead of waiting forever, and never deadlocks a lead being saved.
 *
 * <p>Each test starts from its own schema ({@value #SCHEMA}) at V2, with one car, and holds locks
 * from a separate JDBC connection, as the old instance would. Mutations run for the PR (see {@code ##
 * Notas} of TASK-006): without {@code SET LOCAL lock_timeout} the timeout test fails on {@code
 * assertTimeoutPreemptively}; with {@code LOCK TABLE leads, cars}, or without {@code LOCK TABLE} and
 * the {@code ALTER}s in the order {@code leads}, {@code cars}, the lock-order test fails with a
 * deadlock.
 */
class V3LockTest extends AbstractPostgresIntegrationTest {

	private static final String SCHEMA = "upgrade_v3_lock";
	private static final String V3_SCRIPT = "db/migration/V3__drop_lead_forwarding.sql";
	private static final UUID CAR = UUID.fromString("33333333-3333-3333-3333-333333333333");

	private JdbcTemplate jdbc;

	/** Fresh {@value #SCHEMA} at V2 with one car, for every test. */
	@BeforeEach
	void migrateASchemaToV2WithOneCar() {
		DriverManagerDataSource dataSource =
				new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		dataSource.setSchema(SCHEMA);
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
		flywayTargeting("2").migrate();
		jdbc.update(
				"INSERT INTO cars (id, marca, modelo, ano, preco, km, cor, combustivel, transmissao, origem) "
						+ "VALUES (?, 'Audi', 'A4', 2020, 28000.00, 40000, 'Branco', 'Gasolina', 'Manual', 'Nacional')",
				CAR);
	}

	/**
	 * With a transaction of the old instance reading {@code leads} (it holds {@code ACCESS SHARE}),
	 * V3 gives up after about 10 s with {@code 55P03} and changes nothing; once that transaction
	 * ends, the next start migrates normally.
	 *
	 * @throws SQLException if the side connection fails
	 */
	@Test
	void givesUpAfterTheLockTimeoutWithoutChangingAnythingAndSucceedsOnTheNextStart() throws SQLException {
		try (Connection oldInstance = openTransaction()) {
			try (Statement statement = oldInstance.createStatement()) {
				statement.executeQuery("SELECT count(*) FROM " + SCHEMA + ".leads").close();
			}

			long start = System.nanoTime();
			assertTimeoutPreemptively(Duration.ofSeconds(40), () -> assertThatThrownBy(() -> flywayTargeting("3").migrate())
					.isInstanceOf(FlywayException.class)
					.satisfies(failure -> assertThat(sqlStatesInCauseChain(failure)).contains("55P03")));
			assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofSeconds(9));

			oldInstance.rollback();
		}

		assertThat(currentVersion()).isEqualTo("2");
		assertThat(columnExists("leads", "forwarded_at")).isTrue();

		flywayTargeting("3").migrate();

		assertThat(currentVersion()).isEqualTo("3");
		assertThat(columnExists("leads", "forwarded_at")).isFalse();
	}

	/**
	 * A lead of the old instance halfway through its transaction ({@code FOR KEY SHARE} on the car
	 * done, {@code INSERT} not yet, the order of {@code POST /api/leads}) while V3 waits for its
	 * locks: the {@code INSERT} goes through (no {@code 40P01}), the lead is saved, and V3 completes
	 * right after. With {@code leads} locked before {@code cars} this is a deadlock.
	 *
	 * @throws Exception if the side connection or the migration thread fails
	 */
	@Test
	void aLeadHalfwayThroughItsTransactionIsSavedAndTheMigrationCompletesAfterIt() throws Exception {
		CompletableFuture<Void> migration;
		try (Connection oldInstance = openTransaction()) {
			int oldInstancePid = backendPid(oldInstance);
			try (PreparedStatement lockCar = oldInstance.prepareStatement(
					"SELECT id FROM " + SCHEMA + ".cars WHERE id = ? FOR KEY SHARE")) {
				lockCar.setObject(1, CAR);
				lockCar.executeQuery().close();
			}

			migration = CompletableFuture.runAsync(() -> flywayTargeting("3").migrate());
			awaitAnotherBackendWaitingForALock(oldInstancePid);

			try (PreparedStatement insertLead = oldInstance.prepareStatement(
					"INSERT INTO " + SCHEMA + ".leads (nome, telefone, car_id) VALUES ('Ana', '913456789', ?)")) {
				insertLead.setObject(1, CAR);
				assertThat(insertLead.executeUpdate()).isEqualTo(1);
			}
			oldInstance.commit();
		}

		migration.get(30, TimeUnit.SECONDS);
		assertThat(currentVersion()).isEqualTo("3");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM leads WHERE car_id = ?", Long.class, CAR)).isEqualTo(1);
	}

	/**
	 * AC B.5, shape: the first statement of V3 is exactly the {@code lock_timeout}, so nothing runs
	 * before the timeout applies.
	 *
	 * @throws IOException if the script cannot be read
	 */
	@Test
	void theFirstStatementOfV3IsTheLockTimeout() throws IOException {
		String script;
		try (InputStream input = getClass().getClassLoader().getResourceAsStream(V3_SCRIPT)) {
			assertThat(input).as(V3_SCRIPT).isNotNull();
			script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}

		String firstStatement = script.lines()
				.map(String::strip)
				.filter(line -> !line.isEmpty() && !line.startsWith("--"))
				.findFirst()
				.orElseThrow();

		assertThat(firstStatement).isEqualTo("SET LOCAL lock_timeout = '10s';");
	}

	private Connection openTransaction() throws SQLException {
		Connection connection =
				DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		connection.setAutoCommit(false);
		return connection;
	}

	private static int backendPid(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("SELECT pg_backend_pid()")) {
			resultSet.next();
			return resultSet.getInt(1);
		}
	}

	/** Waits until some backend other than {@code excludedPid} (the migration) waits for a lock. */
	private void awaitAnotherBackendWaitingForALock(int excludedPid) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			Long waiting = jdbc.queryForObject(
					"SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
							+ "AND wait_event_type = 'Lock' AND pid <> ?",
					Long.class,
					excludedPid);
			if (waiting != null && waiting > 0) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("The migration never waited for a lock");
	}

	private static List<String> sqlStatesInCauseChain(Throwable failure) {
		List<String> states = new ArrayList<>();
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sqlException) {
				states.add(sqlException.getSQLState());
			}
		}
		return states;
	}

	private String currentVersion() {
		return jdbc.queryForObject("SELECT max(version) FROM flyway_schema_history WHERE success", String.class);
	}

	private boolean columnExists(String table, String column) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM information_schema.columns "
						+ "WHERE table_schema = ? AND table_name = ? AND column_name = ?)",
				Boolean.class,
				SCHEMA,
				table,
				column));
	}

	private Flyway flywayTargeting(String version) {
		return Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.schemas(SCHEMA)
				.locations("classpath:db/migration")
				.target(version)
				.load();
	}
}
