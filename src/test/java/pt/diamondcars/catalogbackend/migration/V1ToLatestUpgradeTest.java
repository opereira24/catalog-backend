package pt.diamondcars.catalogbackend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * The production upgrade path end to end (TASK-008, C.4, requirement 3): a database at V1 holding
 * real data, as the Neon one was, taken through <em>every</em> migration on the classpath, ends with
 * exactly the catalog of a fresh database, and keeps every V1 row.
 *
 * <p>The fresh database is {@code public}, migrated by the application's own Flyway when the
 * (shared, cached) Spring context starts; that is why this class is a {@code @SpringBootTest}
 * although it never touches a bean. The upgraded one is its own schema ({@value #SCHEMA}), through a
 * {@link DriverManagerDataSource} (no pool), migrated to V1, filled with {@link V1Fixture}, then
 * migrated with no target.
 *
 * <p>What the catalog comparison covers is {@link SchemaCatalog}: columns (type, length, precision,
 * scale, nullability, default), unique indexes, foreign keys with their {@code ON DELETE} action,
 * and {@code CHECK} constraints. The values, column by column, are proven per step by {@link
 * V1ToV2UpgradeTest} and {@link V2ToV3UpgradeTest} and not repeated here. A new migration needs no
 * change in this class.
 *
 * <p>Measured mutants: appending to {@code V3__drop_lead_forwarding.sql} a statement that drops
 * {@code cars.cor NOT NULL} only when there are cars (the fresh database has none, the upgraded one
 * does) turns {@link #upgradedCatalogEqualsTheCatalogOfAFreshDatabase()} red and only it; migrating
 * the upgrade with {@code target("2")} turns {@link #upgradeEndsAtTheLatestMigrationOnTheClasspath()}
 * red.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V1ToLatestUpgradeTest extends AbstractPostgresIntegrationTest {

	private static final String SCHEMA = "upgrade_v1_latest";

	/** The schema the Spring context migrates from empty: the fresh database. */
	private static final String FRESH = "public";

	private static final List<String> TABLES = List.of(
			"app_users", "car_images", "cars", "clients", "leads", "notifications", "partners", "transactions");

	private JdbcTemplate jdbc;
	private List<UUID> carIdsBefore;
	private List<UUID> carImageIdsBefore;
	private List<UUID> leadIdsBefore;

	/** Brings {@value #SCHEMA} to V1, fills it with V1 data, notes its ids, and runs every migration. */
	@BeforeAll
	void upgradeADatabaseHoldingV1DataToTheLatestVersion() {
		DriverManagerDataSource dataSource =
				new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		dataSource.setSchema(SCHEMA);
		jdbc = new JdbcTemplate(dataSource);
		jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");

		flywayTargeting("1").migrate();
		V1Fixture.insertRepresentativeV1Data(jdbc);
		carIdsBefore = ids("cars");
		carImageIdsBefore = ids("car_images");
		leadIdsBefore = ids("leads");

		flywayToLatest().migrate();
	}

	/** The upgrade applies the highest version on the classpath, read from Flyway, not a literal. */
	@Test
	void upgradeEndsAtTheLatestMigrationOnTheClasspath() {
		Flyway flyway = flywayToLatest();
		// all() also lists the "<< Flyway Schema Creation >>" marker, which has no version
		MigrationVersion latest = Arrays.stream(flyway.info().all())
				.map(MigrationInfo::getVersion)
				.filter(Objects::nonNull)
				.max(Comparator.naturalOrder())
				.orElseThrow();

		assertThat(latest).as("highest migration on the classpath").isGreaterThanOrEqualTo(MigrationVersion.fromVersion("3"));
		assertThat(flyway.info().current().getVersion()).as("version the upgraded schema is at").isEqualTo(latest);
		assertThat(flyway.info().pending()).as("migrations not applied").isEmpty();
	}

	/**
	 * V1 + data + every migration gives the same catalog as a fresh database. Both sides are first
	 * checked to have the 8 tables: two empty catalogs would also be equal.
	 */
	@Test
	void upgradedCatalogEqualsTheCatalogOfAFreshDatabase() {
		assertThat(SchemaCatalog.columns(jdbc, SCHEMA).keySet()).as("tables of %s", SCHEMA).containsExactlyElementsOf(TABLES);
		assertThat(SchemaCatalog.columns(jdbc, FRESH).keySet()).as("tables of %s", FRESH).containsExactlyElementsOf(TABLES);

		assertThat(SchemaCatalog.columns(jdbc, SCHEMA)).as("columns").isEqualTo(SchemaCatalog.columns(jdbc, FRESH));
		assertThat(SchemaCatalog.uniqueIndexes(jdbc, SCHEMA)).as("unique indexes").isEqualTo(SchemaCatalog.uniqueIndexes(jdbc, FRESH));
		assertThat(SchemaCatalog.foreignKeys(jdbc, SCHEMA)).as("foreign keys").isEqualTo(SchemaCatalog.foreignKeys(jdbc, FRESH));
		assertThat(SchemaCatalog.checkConstraints(jdbc, SCHEMA))
				.as("check constraints")
				.isNotEmpty()
				.isEqualTo(SchemaCatalog.checkConstraints(jdbc, FRESH));
	}

	/** No V1 car, photo or lead is lost on the way. */
	@Test
	void keepsEveryV1CarPhotoAndLead() {
		assertThat(carIdsBefore).hasSize(2);
		assertThat(carImageIdsBefore).hasSize(3);
		assertThat(leadIdsBefore).hasSize(3);

		assertThat(ids("cars")).containsExactlyInAnyOrderElementsOf(carIdsBefore);
		assertThat(ids("car_images")).containsExactlyInAnyOrderElementsOf(carImageIdsBefore);
		assertThat(ids("leads")).containsExactlyInAnyOrderElementsOf(leadIdsBefore);
	}

	private List<UUID> ids(String table) {
		return jdbc.queryForList("SELECT id FROM " + table, UUID.class);
	}

	private Flyway flywayTargeting(String version) {
		return configuration().target(version).load();
	}

	private Flyway flywayToLatest() {
		return configuration().load();
	}

	private FluentConfiguration configuration() {
		return Flyway.configure()
				.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
				.schemas(SCHEMA)
				.locations("classpath:db/migration");
	}
}
