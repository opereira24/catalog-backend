package pt.diamondcars.catalogbackend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
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
	 * referenced_table}, with the column name the ADR-001 rule expects for it and its {@code ON
	 * DELETE} action ({@link SchemaCatalog#DELETE_RULE}).
	 */
	private static final String FOREIGN_KEYS_SQL =
			"SELECT rel.relname || '.' || att.attname AS fk_column, "
					+ "       ref.relname AS referenced_table, "
					+ "       regexp_replace(ref.relname, 's$', '') || '_id' AS expected_column, "
					+ "       att.attname AS actual_column, "
					+ "       array_length(con.conkey, 1) AS column_count, "
					+ "       " + SchemaCatalog.DELETE_RULE + " AS delete_rule "
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
	 * partner_id}, ...), over every foreign key of {@code pg_constraint}, with no list of exceptions.
	 * Also pins the foreign keys measured on V1+V2 together with their {@code ON DELETE} action
	 * (TASK-008, C.2), so one silently dropped, added, or switched between {@code CASCADE} and {@code
	 * SET NULL} by a future migration fails here too.
	 *
	 * <p>This is the only test of the {@code ON DELETE} actions: the former {@code
	 * renamedForeignKeysStillSetNullOnDelete} checked three of them and was folded into this one.
	 * Measured mutants: {@code notifications.lead_id} to {@code SET NULL} (M13, a deleted lead would
	 * leave its notifications behind) and {@code cars.partner_id} to {@code CASCADE} (M14, deleting a
	 * partner would delete the cars on consignment) both turn this test red.
	 *
	 * @throws AssertionError if any foreign key column breaks the rule, is multi-column, or the set
	 *     of foreign keys or any {@code ON DELETE} action changed
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
		Map<Object, Object> deleteRuleByColumn = new HashMap<>();
		foreignKeys.forEach(fk -> deleteRuleByColumn.put(fk.get("fk_column"), fk.get("delete_rule")));
		assertThat(deleteRuleByColumn)
				.as("one entry per foreign key (a column with two foreign keys would collapse here)")
				.hasSameSizeAs(foreignKeys);
		assertThat(deleteRuleByColumn)
				.as("ON DELETE action of each foreign key")
				.containsExactlyInAnyOrderEntriesOf(Map.of(
						"car_images.car_id", "CASCADE",
						"cars.client_id", "SET NULL",
						"cars.partner_id", "SET NULL",
						"leads.car_id", "SET NULL",
						"notifications.lead_id", "CASCADE",
						"transactions.car_id", "SET NULL",
						"transactions.client_id", "SET NULL",
						"transactions.partner_id", "SET NULL"));
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
}
