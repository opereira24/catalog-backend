package pt.diamondcars.catalogbackend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * Proves the production upgrade path of TASK-006 (AC B.4, D.1): {@code V3__drop_lead_forwarding.sql}
 * applied on top of a database at V2 holding real data drops only {@code cars.synced_at}, {@code
 * leads.forwarded_at} and {@code leads.forward_attempts}, and loses or changes nothing else. In
 * particular, a lead the site saved that was never forwarded ({@code forwarded_at} null after 5
 * attempts) is still there, with every surviving value intact: no lead is lost.
 *
 * <p>Runs in its own schema ({@value #SCHEMA}), never in {@code public}. {@link
 * #upgradeADatabaseHoldingV2Data()} migrates to V2, inserts by SQL, snapshots every column that V3
 * keeps (the column list is read from {@code information_schema.columns} before V3, minus the three
 * dropped ones, so this test needs no maintenance when V2 columns are documented differently), then
 * migrates to V3.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V2ToV3UpgradeTest extends AbstractPostgresIntegrationTest {

	private static final String SCHEMA = "upgrade_v2_v3";

	private static final List<String> TABLES = List.of("cars", "leads", "notifications");

	/** {@code table.column} of the three columns V3 drops. */
	private static final Set<String> DROPPED_COLUMNS =
			Set.of("cars.synced_at", "leads.forwarded_at", "leads.forward_attempts");

	private static final UUID SYNCED_CAR = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID OTHER_CAR = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID LEAD_GIVEN_UP = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID LEAD_FORWARDED = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
	private static final UUID LEAD_GENERAL_CONTACT = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
	private static final UUID NOTIFICATION = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");

	private JdbcTemplate jdbc;
	private final Map<String, String> survivingColumnsByTable = new HashMap<>();
	private final Map<String, List<Map<String, Object>>> rowsBeforeByTable = new HashMap<>();

	/** Brings {@value #SCHEMA} to V2, fills and snapshots it, and upgrades it to V3. */
	@BeforeAll
	void upgradeADatabaseHoldingV2Data() {
		DriverManagerDataSource dataSource =
				new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		dataSource.setSchema(SCHEMA);
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");

		flywayTargeting("2").migrate();
		insertRepresentativeV2Data();
		for (String table : TABLES) {
			String columns = String.join(", ", survivingColumns(table));
			survivingColumnsByTable.put(table, columns);
			rowsBeforeByTable.put(table, jdbc.queryForList("SELECT " + columns + " FROM " + table + " ORDER BY id"));
		}

		flywayTargeting("3").migrate();
	}

	/** The upgrade ends at V3. */
	@Test
	void endsAtVersionThree() {
		assertThat(jdbc.queryForObject("SELECT max(version) FROM flyway_schema_history WHERE success", String.class))
				.isEqualTo("3");
	}

	/**
	 * AC B.2 and D.1: every row of {@code cars}, {@code leads} and {@code notifications} is still
	 * there with the same value in every surviving column, row by row in {@code id} order; the lead
	 * that gave up being forwarded included.
	 */
	@Test
	void keepsEveryRowAndEverySurvivingColumnValue() {
		assertThat(rowsBeforeByTable.get("cars")).hasSize(2);
		assertThat(rowsBeforeByTable.get("leads")).hasSize(3);
		assertThat(rowsBeforeByTable.get("notifications")).hasSize(1);
		assertThat(survivingColumnsByTable.get("leads")).contains("mensagem", "status", "car_id");

		for (String table : TABLES) {
			assertThat(jdbc.queryForList(
							"SELECT " + survivingColumnsByTable.get(table) + " FROM " + table + " ORDER BY id"))
					.as(table)
					.containsExactlyElementsOf(rowsBeforeByTable.get(table));
		}
		assertThat(jdbc.queryForMap("SELECT status, car_id FROM leads WHERE id = ?", LEAD_GIVEN_UP))
				.containsEntry("status", "ativo")
				.containsEntry("car_id", SYNCED_CAR);
	}

	/** The three columns are gone, and the index of {@code forwarded_at} went with its column. */
	@Test
	void dropsTheThreeForwardingColumnsAndTheirIndex() {
		assertThat(jdbc.queryForList(
						"SELECT table_name || '.' || column_name FROM information_schema.columns WHERE table_schema = ?",
						String.class,
						SCHEMA))
				.doesNotContainAnyElementsOf(DROPPED_COLUMNS)
				.contains("cars.observacoes", "leads.mensagem");
		assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ?", String.class, SCHEMA))
				.doesNotContain("idx_leads_forwarded_at")
				.contains("idx_leads_car_id", "idx_leads_status_created_at", "idx_notifications_lead_id");
	}

	private List<String> survivingColumns(String table) {
		return jdbc
				.queryForList(
						"SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?"
								+ " ORDER BY ordinal_position",
						String.class,
						SCHEMA,
						table)
				.stream()
				.filter(column -> !DROPPED_COLUMNS.contains(table + "." + column))
				.toList();
	}

	private Flyway flywayTargeting(String version) {
		return Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.schemas(SCHEMA)
				.locations("classpath:db/migration")
				.target(version)
				.load();
	}

	/**
	 * The V2 shapes production can hold (AC B.4): two cars, one with {@code synced_at}; a lead about
	 * that car that gave up being forwarded ({@code forwarded_at} null, 5 attempts), a lead already
	 * forwarded, and a general contact never forwarded with 0 attempts; one notification of the first
	 * lead.
	 */
	private void insertRepresentativeV2Data() {
		jdbc.update(
				"INSERT INTO cars (id, marca, modelo, ano, preco, km, cor, combustivel, transmissao, origem, "
						+ "observacoes, synced_at, created_at, updated_at) "
						+ "VALUES (?, 'Audi', 'A4', 2020, 28000.50, 40000, 'Branco', 'Gasolina', 'Manual', 'Nacional', "
						+ "'Revisão feita', '2026-02-01T09:00:00Z', '2026-02-01T08:00:00Z', '2026-02-01T08:30:00Z')",
				SYNCED_CAR);
		jdbc.update(
				"INSERT INTO cars (id, marca, modelo, ano, preco, km, cor, combustivel, transmissao, origem, "
						+ "vendido, preco_venda, created_at, updated_at) "
						+ "VALUES (?, 'BMW', '320d', 2019, 25000.00, 60000, 'Preto', 'Diesel', 'Automática', "
						+ "'Importado', true, 24500.00, '2026-01-01T10:00:00Z', '2026-01-02T10:00:00Z')",
				OTHER_CAR);
		jdbc.update(
				"INSERT INTO leads (id, nome, email, telefone, mensagem, car_id, carro_marca, carro_modelo, "
						+ "carro_preco, origem, status, forwarded_at, forward_attempts, created_at, updated_at) "
						+ "VALUES (?, 'Rui', 'rui@example.com', '922222222', 'Ainda disponível?', ?, 'Audi', 'A4', "
						+ "28000.50, 'website', 'ativo', NULL, 5, '2026-03-02T00:00:00Z', '2026-03-02T00:00:00Z')",
				LEAD_GIVEN_UP, SYNCED_CAR);
		jdbc.update(
				"INSERT INTO leads (id, nome, telefone, notas, follow_up_date, origem, status, forwarded_at, "
						+ "forward_attempts, created_at, updated_at) "
						+ "VALUES (?, 'Joana', '911111111', 'Ligar de manhã', '2026-04-01', 'backoffice', "
						+ "'contactado', '2026-03-01T00:05:00Z', 1, '2026-03-01T00:00:00Z', '2026-03-05T00:00:00Z')",
				LEAD_FORWARDED);
		jdbc.update(
				"INSERT INTO leads (id, nome, telefone, mensagem, carro_marca, origem, forward_attempts, created_at) "
						+ "VALUES (?, 'Marta', '933333333', 'Contacto geral', '', 'website-contacto', 0, "
						+ "'2026-03-03T00:00:00Z')",
				LEAD_GENERAL_CONTACT);
		jdbc.update(
				"INSERT INTO notifications (id, tipo, titulo, mensagem, prioridade, lead_id, read, created_at) "
						+ "VALUES (?, 'novo_lead', 'Novo Lead', 'Novo lead de Rui sobre Audi A4', 'media', ?, false, "
						+ "'2026-03-02T00:00:01Z')",
				NOTIFICATION, LEAD_GIVEN_UP);
	}
}
