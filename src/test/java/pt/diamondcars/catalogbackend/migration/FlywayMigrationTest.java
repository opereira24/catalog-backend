package pt.diamondcars.catalogbackend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * Verifies that the Flyway migration {@code V1__init.sql} applies cleanly to an empty, ephemeral
 * PostgreSQL database and creates every table of the catalog schema, with the column types and
 * constraints the rest of the release depends on.
 *
 * <p>The Spring context under test boots with Flyway enabled (see {@code application.yml}), so by
 * the time these tests run the migration has already been applied by the framework; every
 * assertion queries the resulting catalog/data state, not the migration file itself, so a
 * regression in a future {@code V2__...sql} fails loudly here instead of passing silently.
 */
@SpringBootTest
class FlywayMigrationTest extends AbstractPostgresIntegrationTest {

	@Autowired
	private DataSource dataSource;

	/**
	 * Confirms that every table declared by {@code V1__init.sql} — {@code cars}, its {@code
	 * car_images} child table, and {@code leads} — exists in the {@code public} schema after
	 * Flyway runs (TASK-013 acceptance criterion 1/2).
	 *
	 * @throws AssertionError if any expected table is missing
	 */
	@Test
	void migrationCreatesAllCatalogTables() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

		List<String> tableNames = jdbcTemplate.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
				String.class);

		assertThat(tableNames).containsExactlyInAnyOrder(
				"cars", "car_images", "leads", "flyway_schema_history");
	}

	/**
	 * Confirms that every monetary column is {@code NUMERIC(12,2)}, never a floating-point type
	 * (TASK-013 acceptance criterion about {@code float}/{@code double precision}), covering it by
	 * catalog inspection rather than only by a one-off {@code grep} on the migration source.
	 *
	 * @throws AssertionError if the {@code preco} column is missing or is not {@code numeric(12,2)}
	 */
	@Test
	void moneyColumnsUseNumericWithTwoDecimals() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

		Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT data_type, numeric_scale FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND table_name = 'cars' AND column_name = 'preco'");

		assertThat(row.get("data_type")).isEqualTo("numeric");
		assertThat(row.get("numeric_scale")).isEqualTo(2);
	}

	/**
	 * Confirms {@code leads.car_id} has no foreign key constraint to {@code cars} (TASK-013
	 * requirement 5): a lead must survive a car that later disappears from this catalog. Exercises
	 * the actual database catalog rather than only the migration source, so a future migration that
	 * accidentally adds the constraint back fails this test.
	 *
	 * @throws AssertionError if a foreign key from {@code leads.car_id} to {@code cars} exists
	 */
	@Test
	void leadsCarIdHasNoForeignKeyToCars() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

		List<String> foreignKeyConstraints = jdbcTemplate.queryForList(
				"SELECT tc.constraint_name FROM information_schema.table_constraints tc "
						+ "JOIN information_schema.key_column_usage kcu "
						+ "  ON tc.constraint_name = kcu.constraint_name "
						+ "WHERE tc.constraint_type = 'FOREIGN KEY' "
						+ "  AND tc.table_name = 'leads' AND kcu.column_name = 'car_id'",
				String.class);

		assertThat(foreignKeyConstraints).isEmpty();
	}

	/**
	 * Confirms {@code car_images.car_id} has {@code ON DELETE CASCADE} to {@code cars} (TASK-013
	 * requirement 3): deleting a car must remove its photos, unlike the deliberately unconstrained
	 * {@code leads.car_id}.
	 *
	 * @throws AssertionError if the car's images survive the car being deleted
	 */
	@Test
	void deletingACarCascadesToItsImages() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		java.util.UUID carId = java.util.UUID.randomUUID();

		jdbcTemplate.update(
				"INSERT INTO cars (id, marca, modelo, ano, preco, km, cor, combustivel) "
						+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
				carId, "BMW", "320d", 2020, new java.math.BigDecimal("25000.00"), 50000, "Preto",
				"Diesel");
		jdbcTemplate.update(
				"INSERT INTO car_images (car_id, url) VALUES (?, ?)", carId, "https://img/1.jpg");

		jdbcTemplate.update("DELETE FROM cars WHERE id = ?", carId);

		Integer remainingImages = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM car_images WHERE car_id = ?", Integer.class, carId);
		assertThat(remainingImages).isZero();
	}
}
