package pt.diamondcars.catalogbackend.migration;

import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Read-only queries over the PostgreSQL catalog of one schema, shared by {@link
 * FlywayMigrationTest} (the schema of a fresh database, pinned) and {@link V1ToLatestUpgradeTest}
 * (an upgraded database compared with a fresh one) (TASK-008, C.3/C.4). Every result is sorted, so
 * two catalogs compare with {@code isEqualTo} and a failure shows the difference in order.
 *
 * <p>{@code flyway_schema_history} is always left out: it is Flyway's, not the application's, and
 * its rows differ by construction between a database migrated in one go and one migrated in steps.
 *
 * <p>Structured on purpose, never through {@code pg_get_constraintdef} for foreign keys: that
 * function qualifies the referenced table with its schema when the schema is not on the {@code
 * search_path}, so the same foreign key reads differently in two schemas. {@code CHECK} constraints
 * do go through it, because their text names no table.
 */
final class SchemaCatalog {

	/** The table every query leaves out. */
	static final String FLYWAY_HISTORY = "flyway_schema_history";

	/**
	 * {@code ON DELETE} action of the foreign key {@code con} (a {@code pg_constraint} row), as SQL
	 * spells it. {@code confdeltype} is a PostgreSQL {@code "char"}: converted here, in SQL, so no
	 * caller has to map it in Java.
	 */
	static final String DELETE_RULE =
			"CASE con.confdeltype WHEN 'a' THEN 'NO ACTION' WHEN 'r' THEN 'RESTRICT' WHEN 'c' THEN 'CASCADE' "
					+ "WHEN 'n' THEN 'SET NULL' WHEN 'd' THEN 'SET DEFAULT' END";

	private SchemaCatalog() {}

	/**
	 * One column as {@code information_schema.columns} describes it.
	 *
	 * @param dataType e.g. {@code character varying}, {@code numeric}, {@code uuid}
	 * @param maxLength {@code character_maximum_length}, {@code null} for non-character types
	 * @param numericPrecision {@code null} for non-numeric types
	 * @param numericScale {@code null} for non-numeric types
	 * @param nullable {@code is_nullable = 'YES'}
	 * @param defaultValue {@code column_default}, {@code null} without a default
	 */
	record Column(
			String dataType,
			Integer maxLength,
			Integer numericPrecision,
			Integer numericScale,
			boolean nullable,
			String defaultValue) {}

	/**
	 * A single-column foreign key, from the referencing side.
	 *
	 * @param referencedTable the table it points to
	 * @param deleteRule {@code ON DELETE} action, as SQL spells it ({@code CASCADE}, {@code SET NULL}, ...)
	 */
	record ForeignKey(String referencedTable, String deleteRule) {}

	/**
	 * Every column, by table.
	 *
	 * @param jdbc any connection to the database
	 * @param schema the schema to read
	 * @return {@code table -> column -> description}
	 */
	static SortedMap<String, SortedMap<String, Column>> columns(JdbcTemplate jdbc, String schema) {
		SortedMap<String, SortedMap<String, Column>> columns = new TreeMap<>();
		jdbc.query(
				"SELECT table_name, column_name, data_type, character_maximum_length, numeric_precision, "
						+ "numeric_scale, is_nullable, column_default "
						+ "FROM information_schema.columns WHERE table_schema = ? AND table_name <> ?",
				row -> {
					columns.computeIfAbsent(row.getString("table_name"), table -> new TreeMap<>())
							.put(
									row.getString("column_name"),
									new Column(
											row.getString("data_type"),
											row.getObject("character_maximum_length", Integer.class),
											row.getObject("numeric_precision", Integer.class),
											row.getObject("numeric_scale", Integer.class),
											"YES".equals(row.getString("is_nullable")),
											row.getString("column_default")));
				},
				schema,
				FLYWAY_HISTORY);
		return columns;
	}

	/**
	 * The column names of each table.
	 *
	 * @param jdbc any connection to the database
	 * @param schema the schema to read
	 * @return {@code table -> column names}
	 */
	static SortedMap<String, Set<String>> columnNames(JdbcTemplate jdbc, String schema) {
		return namesWhere(columns(jdbc, schema), false);
	}

	/**
	 * The {@code NOT NULL} column names of each table (a table with none maps to an empty set).
	 *
	 * @param jdbc any connection to the database
	 * @param schema the schema to read
	 * @return {@code table -> NOT NULL column names}
	 */
	static SortedMap<String, Set<String>> notNullColumnNames(JdbcTemplate jdbc, String schema) {
		return namesWhere(columns(jdbc, schema), true);
	}

	/**
	 * Every unique index (primary keys included) as {@code table(column, column)}, columns in index
	 * order, with {@code WHERE <predicate>} appended for a partial one.
	 *
	 * @param jdbc any connection to the database
	 * @param schema the schema to read
	 * @return the unique indexes
	 */
	static SortedSet<String> uniqueIndexes(JdbcTemplate jdbc, String schema) {
		return new TreeSet<>(jdbc.queryForList(
				"SELECT t.relname || '(' || "
						+ "  (SELECT string_agg(a.attname, ', ' ORDER BY k.ord) "
						+ "   FROM unnest(i.indkey::int2[]) WITH ORDINALITY AS k(attnum, ord) "
						+ "   JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum) || ')' "
						+ "  || COALESCE(' WHERE ' || pg_get_expr(i.indpred, i.indrelid), '') "
						+ "FROM pg_index i "
						+ "JOIN pg_class t ON t.oid = i.indrelid "
						+ "JOIN pg_namespace n ON n.oid = t.relnamespace "
						+ "WHERE i.indisunique AND n.nspname = ? AND t.relname <> ?",
				String.class,
				schema,
				FLYWAY_HISTORY));
	}

	/**
	 * Every foreign key, as {@code table.column -> (referenced table, ON DELETE action)}. The
	 * {@code ON DELETE} action comes from {@link #DELETE_RULE}.
	 *
	 * @param jdbc any connection to the database
	 * @param schema the schema to read
	 * @return the foreign keys
	 */
	static SortedMap<String, ForeignKey> foreignKeys(JdbcTemplate jdbc, String schema) {
		SortedMap<String, ForeignKey> foreignKeys = new TreeMap<>();
		jdbc.query(
				"SELECT rel.relname || '.' || att.attname AS fk_column, ref.relname AS referenced_table, "
						+ DELETE_RULE + " AS delete_rule "
						+ "FROM pg_constraint con "
						+ "JOIN pg_class rel ON rel.oid = con.conrelid "
						+ "JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace "
						+ "JOIN pg_class ref ON ref.oid = con.confrelid "
						+ "JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = con.conkey[1] "
						+ "WHERE con.contype = 'f' AND nsp.nspname = ?",
				row -> {
					foreignKeys.put(
							row.getString("fk_column"),
							new ForeignKey(row.getString("referenced_table"), row.getString("delete_rule")));
				},
				schema);
		return foreignKeys;
	}

	/**
	 * Every {@code CHECK} constraint, as {@code table.constraint_name -> definition}. The name is
	 * part of the contract: tests and error handling match on it (e.g. {@code leads_origem_check}).
	 *
	 * @param jdbc any connection to the database
	 * @param schema the schema to read
	 * @return the check constraints
	 */
	static SortedMap<String, String> checkConstraints(JdbcTemplate jdbc, String schema) {
		SortedMap<String, String> checks = new TreeMap<>();
		jdbc.query(
				"SELECT rel.relname || '.' || con.conname AS name, pg_get_constraintdef(con.oid) AS definition "
						+ "FROM pg_constraint con "
						+ "JOIN pg_class rel ON rel.oid = con.conrelid "
						+ "JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace "
						+ "WHERE con.contype = 'c' AND nsp.nspname = ? AND rel.relname <> ?",
				row -> {
					checks.put(row.getString("name"), row.getString("definition"));
				},
				schema,
				FLYWAY_HISTORY);
		return checks;
	}

	private static SortedMap<String, Set<String>> namesWhere(
			Map<String, ? extends Map<String, Column>> columns, boolean notNullOnly) {
		SortedMap<String, Set<String>> names = new TreeMap<>();
		columns.forEach((table, byName) -> {
			SortedSet<String> selected = new TreeSet<>();
			byName.forEach((name, column) -> {
				if (!notNullOnly || !column.nullable()) {
					selected.add(name);
				}
			});
			names.put(table, selected);
		});
		return names;
	}
}
