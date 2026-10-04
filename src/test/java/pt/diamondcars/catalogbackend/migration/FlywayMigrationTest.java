package pt.diamondcars.catalogbackend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * Verifies that the Flyway migrations ({@code V1__init.sql} + {@code
 * V2__unified_back_office_schema.sql}) apply cleanly to an empty, ephemeral PostgreSQL database and
 * create every table of the unified schema, with the column types and constraints the rest of the
 * release depends on.
 *
 * <p>The Spring context under test boots with Flyway enabled (see {@code application.yml}), so by
 * the time these tests run the migrations have already been applied by the framework; every
 * assertion queries the resulting catalog/data state, not the migration files themselves, so a
 * regression in a future migration fails loudly here instead of passing silently. The upgrade of
 * a database that already holds V1 data is covered separately by {@link V1ToV2UpgradeTest}.
 */
@SpringBootTest
class FlywayMigrationTest extends AbstractPostgresIntegrationTest {

	@Autowired
	private DataSource dataSource;

	/**
	 * Confirms that every table of the unified schema — the catalog's {@code cars}, {@code
	 * car_images} and {@code leads}, plus the back-office tables V2 creates — exists in the {@code
	 * public} schema after Flyway runs, and nothing else.
	 *
	 * @throws AssertionError if any expected table is missing, or an unexpected one exists
	 */
	@Test
	void migrationCreatesAllTablesOfTheUnifiedSchema() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

		List<String> tableNames = jdbcTemplate.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
				String.class);

		assertThat(tableNames).containsExactlyInAnyOrder(
				"cars",
				"car_images",
				"leads",
				"partners",
				"clients",
				"transactions",
				"notifications",
				"app_users",
				"flyway_schema_history");
	}

	/**
	 * Confirms that every monetary column of the schema is {@code NUMERIC(12,2)}, never a
	 * floating-point type, by catalog inspection rather than only by a one-off {@code grep} on the
	 * migration source. Each column is named with its table, so a money column added to the wrong
	 * table, or a missing one, fails here.
	 *
	 * @throws AssertionError if any known monetary column is missing or is not {@code numeric(12,2)}
	 */
	@Test
	void moneyColumnsUseNumericWithTwoDecimals() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		List<String> moneyColumns = List.of(
				"cars.preco",
				"cars.preco_compra",
				"cars.commission_value",
				"cars.preco_venda",
				"leads.carro_preco",
				"transactions.valor",
				"partners.total_commission");

		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT table_name || '.' || column_name AS qualified_name, data_type, numeric_precision, "
						+ "numeric_scale FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND table_name || '.' || column_name = ANY (?)",
				(Object) moneyColumns.toArray(new String[0]));

		assertThat(rows)
				.extracting(row -> row.get("qualified_name"))
				.containsExactlyInAnyOrderElementsOf(moneyColumns);
		assertThat(rows).allSatisfy(row -> {
			assertThat(row.get("data_type")).isEqualTo("numeric");
			assertThat(row.get("numeric_precision")).isEqualTo(12);
			assertThat(row.get("numeric_scale")).isEqualTo(2);
		});
	}

	/**
	 * Confirms {@code leads.car_id} is a foreign key to {@code cars} with {@code ON DELETE SET
	 * NULL} (V2, TASK-001): a lead references a real car, and deleting the car keeps the lead.
	 *
	 * @throws AssertionError if the foreign key is missing, points elsewhere, or does not set null
	 */
	@Test
	void leadsCarIdIsAForeignKeyToCarsThatSetsNullOnDelete() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

		List<Map<String, Object>> foreignKeys = jdbcTemplate.queryForList(
				"SELECT con.conname, con.confrelid::regclass::text AS referenced_table, "
						+ "rc.delete_rule "
						+ "FROM pg_constraint con "
						+ "JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1] "
						+ "JOIN information_schema.referential_constraints rc "
						+ "  ON rc.constraint_name = con.conname AND rc.constraint_schema = 'public' "
						+ "WHERE con.contype = 'f' AND con.conrelid = 'public.leads'::regclass "
						+ "  AND att.attname = 'car_id'");

		assertThat(foreignKeys).hasSize(1);
		assertThat(foreignKeys.get(0))
				.containsEntry("conname", "leads_car_id_fkey")
				.containsEntry("referenced_table", "cars")
				.containsEntry("delete_rule", "SET NULL");
	}

	/**
	 * Confirms {@code car_images.car_id} has {@code ON DELETE CASCADE} to {@code cars}: deleting a
	 * car must remove its photos.
	 *
	 * @throws AssertionError if the car's images survive the car being deleted
	 */
	@Test
	void deletingACarCascadesToItsImages() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

		UUID carId = jdbcTemplate.queryForObject(
				"INSERT INTO cars (marca, modelo, ano, preco, km, cor, combustivel, transmissao, origem) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
				UUID.class, "BMW", "320d", 2020, new BigDecimal("25000.00"), 50000, "Preto", "Diesel",
				"Manual", "Nacional");
		jdbcTemplate.update(
				"INSERT INTO car_images (car_id, url) VALUES (?, ?)", carId, "https://img/1.jpg");

		jdbcTemplate.update("DELETE FROM cars WHERE id = ?", carId);

		Integer remainingImages = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM car_images WHERE car_id = ?", Integer.class, carId);
		assertThat(remainingImages).isZero();
	}

	/**
	 * Confirms that {@code app_users.auth_subject} enforces uniqueness at the database level, by
	 * behaviour rather than by inspecting the migration SQL: inserting two users with the same Auth0
	 * subject must fail. Ported from {@code dcbo-backend}.
	 *
	 * @throws AssertionError if a duplicate {@code auth_subject} is accepted
	 */
	@Test
	void authSubjectIsUnique() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		String sharedAuthSubject = "auth0|" + UUID.randomUUID();

		jdbcTemplate.update(
				"INSERT INTO app_users (auth_subject, email, name, role) VALUES (?, ?, ?, ?)",
				sharedAuthSubject, "first@example.com", "First User", "admin");

		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO app_users (auth_subject, email, name, role) VALUES (?, ?, ?, ?)",
				sharedAuthSubject, "second@example.com", "Second User", "user"))
				.isInstanceOf(DuplicateKeyException.class);
	}

	/**
	 * Confirms that deleting a client never deletes the financial history tied to them, only
	 * detaches it ({@code transactions.client_id ON DELETE SET NULL}). Ported from {@code
	 * dcbo-backend}.
	 *
	 * @throws AssertionError if the transaction is removed, or its {@code client_id} is not nulled
	 *     out, after the referenced client is deleted
	 */
	@Test
	void deletingAClientDoesNotDeleteItsFinancialHistory() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

		UUID clientId = jdbcTemplate.queryForObject(
				"INSERT INTO clients (name, phone) VALUES (?, ?) RETURNING id",
				UUID.class, "Cliente Teste", "912345678");
		UUID transactionId = jdbcTemplate.queryForObject(
				"INSERT INTO transactions (tipo, valor, data, client_id) "
						+ "VALUES (?, ?, CURRENT_DATE, ?) RETURNING id",
				UUID.class, "receita", new BigDecimal("100.00"), clientId);

		jdbcTemplate.update("DELETE FROM clients WHERE id = ?", clientId);

		Map<String, Object> survivingTransaction = jdbcTemplate.queryForMap(
				"SELECT client_id FROM transactions WHERE id = ?", transactionId);
		assertThat(survivingTransaction.get("client_id")).isNull();
	}
}
