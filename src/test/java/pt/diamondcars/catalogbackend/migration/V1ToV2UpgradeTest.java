package pt.diamondcars.catalogbackend.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * Proves the production upgrade path of TASK-001 (requirement 2): {@code
 * V2__unified_back_office_schema.sql} applied on top of a database that is already at V1 and holds
 * real catalog data — as the Neon database does — loses and alters nothing it must not.
 *
 * <p>Runs in its own PostgreSQL schema ({@value #SCHEMA}) of the shared Testcontainers database,
 * so it never touches the {@code public} schema the Spring-context tests migrate. {@link
 * #upgradeADatabaseHoldingV1Data()} migrates to V1 only, inserts representative V1 rows by SQL
 * (the only writer V1 ever had), snapshots every V1 column, then runs V2. The tests then compare
 * against that snapshot; the last one ({@link Order} 99) writes data and therefore runs last.
 *
 * <p>The upgrade targets V2 explicitly ({@code target("2")}): this test is about the V1 to V2
 * step, and later migrations (the cleanup one drops {@code forwarded_at}/{@code
 * forward_attempts}/{@code synced_at}) must not change what it proves.
 *
 * <p>Mutation evidence (TASK-001, {@code ## Notas}): changing the {@code status} default to {@code
 * 'contactado'} turns {@link #backfillsEveryLeadAsAtivoWithUpdatedAtEqualToCreatedAt()} red;
 * removing the orphan {@code UPDATE} makes the V2 migration itself fail on {@code
 * leads_car_id_fkey}, failing {@link #upgradeADatabaseHoldingV1Data()}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class V1ToV2UpgradeTest extends AbstractPostgresIntegrationTest {

	private static final String SCHEMA = "upgrade_v1_v2";

	private static final UUID SOLD_CAR = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID CAR_WITH_NOTES = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID LEAD_WITH_CAR = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
	private static final UUID LEAD_WITH_ORPHAN_CAR =
			UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
	private static final UUID LEAD_WITHOUT_CAR = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003");
	private static final UUID DELETED_CAR = UUID.fromString("99999999-9999-9999-9999-999999999999");

	private static final String V1_CARS_COLUMNS =
			"id, marca, modelo, ano, preco, km, cor, combustivel, descricao, observacoes, garantia_meses,"
					+ " vendido, reservado, destaque, synced_at, created_at, updated_at";
	private static final String V1_CAR_IMAGES_COLUMNS = "id, car_id, url, thumbnail_url, position";

	/**
	 * Every V1 column of {@code leads} except {@code car_id}, which V2 deliberately changes for
	 * orphans (to {@code NULL}); {@code car_id} is asserted lead by lead instead.
	 */
	private static final String V1_LEADS_COLUMNS_EXCEPT_CAR_ID =
			"id, nome, email, telefone, mensagem, carro_marca, carro_modelo, origem, forwarded_at,"
					+ " forward_attempts, created_at";

	private JdbcTemplate jdbc;
	private List<Map<String, Object>> carsBefore;
	private List<Map<String, Object>> carImagesBefore;
	private List<Map<String, Object>> leadsBefore;

	/**
	 * Brings {@value #SCHEMA} to V1, fills it with representative V1 data, snapshots it, and
	 * upgrades it to V2 — the exact sequence the first deploy of this code runs against Neon.
	 */
	@BeforeAll
	void upgradeADatabaseHoldingV1Data() {
		DriverManagerDataSource dataSource =
				new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		dataSource.setSchema(SCHEMA);
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");

		flywayTargeting("1").migrate();
		insertRepresentativeV1Data();
		carsBefore = jdbc.queryForList("SELECT " + V1_CARS_COLUMNS + " FROM cars ORDER BY id");
		carImagesBefore = jdbc.queryForList("SELECT " + V1_CAR_IMAGES_COLUMNS + " FROM car_images ORDER BY id");
		leadsBefore =
				jdbc.queryForList("SELECT " + V1_LEADS_COLUMNS_EXCEPT_CAR_ID + " FROM leads ORDER BY id");

		flywayTargeting("2").migrate();
	}

	/**
	 * Requirement 2: no row is lost and no V1 column of any existing car, photo or lead changes
	 * value — the comparison runs on every V1 column, row by row, in id order.
	 */
	@Test
	@Order(1)
	void keepsEveryV1RowAndEveryV1ColumnValue() {
		assertThat(jdbc.queryForObject(
						"SELECT max(version) FROM flyway_schema_history WHERE success", String.class))
				.isEqualTo("2");
		assertThat(carsBefore).hasSize(2);
		assertThat(carImagesBefore).hasSize(3);
		assertThat(leadsBefore).hasSize(3);

		assertThat(jdbc.queryForList("SELECT " + V1_CARS_COLUMNS + " FROM cars ORDER BY id"))
				.containsExactlyElementsOf(carsBefore);
		assertThat(jdbc.queryForList("SELECT " + V1_CAR_IMAGES_COLUMNS + " FROM car_images ORDER BY id"))
				.containsExactlyElementsOf(carImagesBefore);
		assertThat(jdbc.queryForList(
						"SELECT " + V1_LEADS_COLUMNS_EXCEPT_CAR_ID + " FROM leads ORDER BY id"))
				.containsExactlyElementsOf(leadsBefore);
	}

	/**
	 * Cars from before V2 get {@code Manual}/{@code Nacional} (the back-office form's initial
	 * values), and the backfill default is gone afterwards; {@code cars.id} is now generated by
	 * the database.
	 */
	@Test
	@Order(2)
	void backfillsTransmissaoAndOrigemThenDropsTheirDefaultsAndGeneratesCarIds() {
		assertThat(jdbc.queryForList("SELECT transmissao, origem FROM cars"))
				.hasSize(2)
				.allSatisfy(row -> assertThat(row)
						.containsEntry("transmissao", "Manual")
						.containsEntry("origem", "Nacional"));

		Map<String, String> defaults = columnDefaults("cars");
		assertThat(defaults.get("transmissao")).isNull();
		assertThat(defaults.get("origem")).isNull();
		assertThat(defaults.get("id")).isEqualTo("gen_random_uuid()");
	}

	/**
	 * Every lead from before V2 is {@code ativo} — what the site always wrote, never {@code
	 * contactado}, which would claim a contact that never happened — and has never been edited
	 * ({@code updated_at = created_at}).
	 */
	@Test
	@Order(3)
	void backfillsEveryLeadAsAtivoWithUpdatedAtEqualToCreatedAt() {
		assertThat(jdbc.queryForList("SELECT status, updated_at = created_at AS never_edited FROM leads"))
				.hasSize(3)
				.allSatisfy(row -> assertThat(row)
						.containsEntry("status", "ativo")
						.containsEntry("never_edited", true));
		assertThat(columnDefaults("leads").get("status")).isEqualTo("'ativo'::character varying");
	}

	/**
	 * A photo's real date never existed; V2 gives each pre-existing photo its car's {@code
	 * created_at}, the honest lower bound, never the time of the migration.
	 */
	@Test
	@Order(4)
	void backfillsEveryCarImageCreatedAtWithItsCarsCreatedAt() {
		assertThat(jdbc.queryForObject(
						"SELECT count(*) FROM car_images ci JOIN cars c ON c.id = ci.car_id "
								+ "WHERE ci.created_at = c.created_at",
						Integer.class))
				.isEqualTo(3);
	}

	/**
	 * The lead whose car no longer exists loses only the dangling {@code car_id} (the foreign key
	 * could not be created otherwise) and keeps its snapshot; the lead with a real car keeps it; the
	 * general contact stays without car. No lead is deleted.
	 */
	@Test
	@Order(5)
	void clearsOnlyOrphanCarIdsAndKeepsEveryLeadAndItsSnapshot() {
		assertThat(leadCarId(LEAD_WITH_CAR)).isEqualTo(CAR_WITH_NOTES);
		assertThat(leadCarId(LEAD_WITH_ORPHAN_CAR)).isNull();
		assertThat(leadCarId(LEAD_WITHOUT_CAR)).isNull();
		assertThat(jdbc.queryForMap(
						"SELECT carro_marca, carro_modelo, forward_attempts FROM leads WHERE id = ?",
						LEAD_WITH_ORPHAN_CAR))
				.containsEntry("carro_marca", "Fiat")
				.containsEntry("carro_modelo", "Punto")
				.containsEntry("forward_attempts", 5);
	}

	/**
	 * The constraints V2 adds or changes hold on the upgraded database, not only on a fresh one:
	 * {@code origem} accepts {@code backoffice} and still rejects unknown values, {@code status}
	 * rejects values outside the pipeline, {@code car_id} must reference a real car, a car needs
	 * a {@code transmissao}, and deleting a car keeps its leads (without car) while deleting its
	 * photos. Runs last: it writes data.
	 */
	@Test
	@Order(99)
	void enforcesTheNewConstraintsOnTheUpgradedDatabase() {
		jdbc.update("INSERT INTO leads (nome, telefone, origem) VALUES ('Back Office', '911000000', 'backoffice')");

		assertThatThrownBy(() -> jdbc.update(
						"INSERT INTO leads (nome, telefone, origem) VALUES ('Origem Errada', '911000001', 'xpto')"))
				.hasMessageContaining("leads_origem_check");
		assertThatThrownBy(() -> jdbc.update(
						"INSERT INTO leads (nome, telefone, status) VALUES ('Estado Errado', '911000002', 'novo')"))
				.hasMessageContaining("leads_status_check");
		assertThatThrownBy(() -> jdbc.update(
						"INSERT INTO leads (nome, telefone, car_id) VALUES ('Carro Inexistente', '911000003', ?)",
						UUID.randomUUID()))
				.hasMessageContaining("leads_car_id_fkey");
		assertThatThrownBy(() -> jdbc.update(
						"INSERT INTO cars (marca, modelo, ano, preco, km, cor, combustivel, origem) "
								+ "VALUES ('Fiat', 'Uno', 2001, 1000, 200000, 'Azul', 'Gasolina', 'Nacional')"))
				.hasMessageContaining("transmissao");

		jdbc.update("DELETE FROM cars WHERE id = ?", CAR_WITH_NOTES);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM leads WHERE id = ?", Integer.class, LEAD_WITH_CAR))
				.isEqualTo(1);
		assertThat(leadCarId(LEAD_WITH_CAR)).isNull();
		assertThat(jdbc.queryForObject(
						"SELECT count(*) FROM car_images WHERE car_id = ?", Integer.class, CAR_WITH_NOTES))
				.isZero();
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
	 * Inserts the V1 shapes the production data can have: a sold car and a car with {@code
	 * observacoes}/{@code synced_at}, with photos; a lead about an existing car already forwarded,
	 * a lead about a car that no longer exists (V1 had no foreign key) that exhausted its forwarding
	 * attempts, and a general contact without car.
	 */
	private void insertRepresentativeV1Data() {
		jdbc.update(
				"INSERT INTO cars (id, marca, modelo, ano, preco, km, cor, combustivel, vendido, created_at, updated_at) "
						+ "VALUES (?, 'BMW', '320d', 2019, 25000.00, 60000, 'Preto', 'Diesel', true, "
						+ "'2026-01-01T10:00:00Z', '2026-01-02T10:00:00Z')",
				SOLD_CAR);
		jdbc.update(
				"INSERT INTO cars (id, marca, modelo, ano, preco, km, cor, combustivel, descricao, observacoes, "
						+ "garantia_meses, destaque, synced_at, created_at, updated_at) "
						+ "VALUES (?, 'Audi', 'A4', 2020, 28000.50, 40000, 'Branco', 'Gasolina', 'Como novo', "
						+ "'Revisão feita', 12, true, '2026-02-01T09:00:00Z', '2026-02-01T08:00:00Z', "
						+ "'2026-02-01T08:30:00Z')",
				CAR_WITH_NOTES);
		jdbc.update(
				"INSERT INTO car_images (car_id, url, thumbnail_url, position) VALUES "
						+ "(?, 'https://img/bmw-1.jpg', NULL, 0), "
						+ "(?, 'https://img/audi-1.jpg', 'https://img/audi-1-t.jpg', 0), "
						+ "(?, 'https://img/audi-2.jpg', NULL, 1)",
				SOLD_CAR, CAR_WITH_NOTES, CAR_WITH_NOTES);
		jdbc.update(
				"INSERT INTO leads (id, nome, email, telefone, mensagem, car_id, carro_marca, carro_modelo, "
						+ "origem, forwarded_at, created_at) "
						+ "VALUES (?, 'Joana', 'joana@example.com', '911111111', 'Ainda disponível?', ?, 'Audi', "
						+ "'A4', 'website', '2026-03-01T00:05:00Z', '2026-03-01T00:00:00Z')",
				LEAD_WITH_CAR, CAR_WITH_NOTES);
		jdbc.update(
				"INSERT INTO leads (id, nome, telefone, car_id, carro_marca, carro_modelo, origem, "
						+ "forward_attempts, created_at) "
						+ "VALUES (?, 'Rui', '922222222', ?, 'Fiat', 'Punto', 'website', 5, '2026-03-02T00:00:00Z')",
				LEAD_WITH_ORPHAN_CAR, DELETED_CAR);
		jdbc.update(
				"INSERT INTO leads (id, nome, telefone, mensagem, origem, created_at) "
						+ "VALUES (?, 'Marta', '933333333', 'Contacto geral', 'website-contacto', "
						+ "'2026-03-03T00:00:00Z')",
				LEAD_WITHOUT_CAR);
	}

	private UUID leadCarId(UUID leadId) {
		return jdbc.queryForObject("SELECT car_id FROM leads WHERE id = ?", UUID.class, leadId);
	}

	private Map<String, String> columnDefaults(String table) {
		Map<String, String> defaults = new HashMap<>();
		jdbc.query(
				"SELECT column_name, column_default FROM information_schema.columns "
						+ "WHERE table_schema = ? AND table_name = ?",
				row -> {
					defaults.put(row.getString("column_name"), row.getString("column_default"));
				},
				SCHEMA,
				table);
		return defaults;
	}
}
