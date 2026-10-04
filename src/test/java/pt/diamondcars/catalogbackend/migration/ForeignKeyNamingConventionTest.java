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
 * Locks in ADR-001, layer 1 ({@code projects/diamondcars/project.md}): every foreign-key column of
 * the {@code public} schema is named in English after the table it references ({@code
 * <referenced_table_singular>_id}). Ported from {@code dcbo-backend} (the Portuguese-name blacklist,
 * the English columns that replaced {@code cliente_id}/{@code carro_id}, their {@code ON DELETE SET
 * NULL}) plus the positive rule, checked over every foreign key the database actually has
 * (TASK-001, AC D.3).
 *
 * <p>This test does not police business attributes (ADR-001, layer 2, e.g. {@code marca}, {@code
 * preco}, {@code carro_marca}) — those stay in Portuguese, mirroring the {@code dc}/{@code dcbo}
 * frontends.
 */
@SpringBootTest
class ForeignKeyNamingConventionTest extends AbstractPostgresIntegrationTest {

	/**
	 * Every single-column foreign key of the {@code public} schema, as {@code table.column ->
	 * referenced_table}, with the column name the ADR-001 rule expects for it.
	 */
	private static final String FOREIGN_KEYS_SQL =
			"SELECT rel.relname || '.' || att.attname AS fk_column, "
					+ "       ref.relname AS referenced_table, "
					+ "       regexp_replace(ref.relname, 's$', '') || '_id' AS expected_column, "
					+ "       att.attname AS actual_column, "
					+ "       array_length(con.conkey, 1) AS column_count "
					+ "FROM pg_constraint con "
					+ "JOIN pg_class rel ON rel.oid = con.conrelid "
					+ "JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace "
					+ "JOIN pg_class ref ON ref.oid = con.confrelid "
					+ "JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1] "
					+ "WHERE con.contype = 'f' AND nsp.nspname = 'public'";

	@Autowired
	private DataSource dataSource;

	/**
	 * The positive rule: for every foreign key in {@code pg_constraint}, the column is named {@code
	 * <referenced table without its final s>_id} ({@code cars -> car_id}, {@code partners ->
	 * partner_id}, ...). Also pins the foreign keys measured on V1+V2, so one silently dropped by a
	 * future migration fails here too.
	 *
	 * @throws AssertionError if any foreign key column breaks the rule, is multi-column, or the set
	 *     of foreign keys changed
	 */
	@Test
	void everyForeignKeyColumnIsNamedAfterTheTableItReferences() {
		List<Map<String, Object>> foreignKeys = new JdbcTemplate(dataSource).queryForList(FOREIGN_KEYS_SQL);

		assertThat(foreignKeys)
				.as("every FK column must be <referenced_table_singular>_id (ADR-001, layer 1): %s", foreignKeys)
				.isNotEmpty()
				.allSatisfy(fk -> {
					assertThat(fk.get("column_count")).isEqualTo(1);
					assertThat(fk.get("actual_column")).isEqualTo(fk.get("expected_column"));
				});
		assertThat(foreignKeys)
				.extracting(fk -> fk.get("fk_column"))
				.containsExactlyInAnyOrder(
						"car_images.car_id",
						"cars.client_id",
						"cars.partner_id",
						"leads.car_id",
						"notifications.lead_id",
						"transactions.car_id",
						"transactions.client_id",
						"transactions.partner_id");
	}

	/**
	 * Confirms that none of the Portuguese FK names known to have existed (or that could plausibly
	 * be reintroduced by mistake) are present anywhere in the {@code public} schema.
	 *
	 * @throws AssertionError if any blacklisted, Portuguese-named column exists
	 */
	@Test
	void noForeignKeyColumnUsesAPortugueseName() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		List<String> portugueseFkBlacklist = List.of(
				"cliente_id", "carro_id", "parceiro_id", "utilizador_id", "transacao_id", "imagem_id");

		List<Map<String, Object>> matches = jdbcTemplate.queryForList(
				"SELECT table_name, column_name FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND column_name = ANY (?)",
				(Object) portugueseFkBlacklist.toArray(new String[0]));

		assertThat(matches)
				.as("no FK column may use a Portuguese name (ADR-001, layer 1): %s", matches)
				.isEmpty();
	}

	/**
	 * Confirms the English columns that {@code dcbo-backend} had to rename into ({@code
	 * cars.client_id}, {@code transactions.client_id}, {@code leads.car_id}) exist here with those
	 * names, and the old Portuguese names do not.
	 *
	 * @throws AssertionError if any of the columns is missing, or an old name exists
	 */
	@Test
	void renamedForeignKeyColumnsExistAndOldNamesAreGone() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

		List<String> renamedColumns = jdbcTemplate.queryForList(
				"SELECT table_name || '.' || column_name FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND ("
						+ "(table_name = 'cars' AND column_name = 'client_id') OR "
						+ "(table_name = 'transactions' AND column_name = 'client_id') OR "
						+ "(table_name = 'leads' AND column_name = 'car_id'))",
				String.class);

		assertThat(renamedColumns)
				.containsExactlyInAnyOrder("cars.client_id", "transactions.client_id", "leads.car_id");

		List<String> oldColumns = jdbcTemplate.queryForList(
				"SELECT table_name || '.' || column_name FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND column_name IN ('cliente_id', 'carro_id')",
				String.class);

		assertThat(oldColumns).isEmpty();
	}

	/**
	 * Confirms those three foreign keys detach rather than delete ({@code ON DELETE SET NULL}):
	 * deleting a client or a car never deletes a car, a transaction or a lead.
	 *
	 * @throws AssertionError if any of the three FK columns has no foreign key, or the {@code
	 *     delete_rule} is not {@code SET NULL}
	 */
	@Test
	void renamedForeignKeysStillSetNullOnDelete() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

		List<Map<String, Object>> foreignKeys = jdbcTemplate.queryForList(
				"SELECT tc.table_name, kcu.column_name, rc.delete_rule "
						+ "FROM information_schema.table_constraints tc "
						+ "JOIN information_schema.key_column_usage kcu "
						+ "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema "
						+ "JOIN information_schema.referential_constraints rc "
						+ "  ON tc.constraint_name = rc.constraint_name AND tc.table_schema = rc.constraint_schema "
						+ "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public' "
						+ "  AND ((tc.table_name = 'cars' AND kcu.column_name = 'client_id') "
						+ "    OR (tc.table_name = 'transactions' AND kcu.column_name = 'client_id') "
						+ "    OR (tc.table_name = 'leads' AND kcu.column_name = 'car_id'))");

		assertThat(foreignKeys)
				.as("the three FK columns must keep their ON DELETE SET NULL constraint: %s", foreignKeys)
				.hasSize(3)
				.allSatisfy(row -> assertThat(row.get("delete_rule")).isEqualTo("SET NULL"));
	}
}
