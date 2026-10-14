package pt.diamondcars.catalogbackend.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that need a real, running PostgreSQL database.
 *
 * <p>A single {@link PostgreSQLContainer} is started once, in a static initializer, and shared by
 * every test class that extends this one (the "singleton container" pattern), instead of one
 * container being started per test class. The container is never stopped explicitly: Testcontainers'
 * Ryuk reaper removes it automatically once the JVM exits.
 *
 * <p>This container is strictly local, ephemeral and disposable — it runs entirely inside Docker on
 * the developer/CI machine and holds no data of value. It must never be pointed at Neon, or at any
 * other real/remote database: {@link ServiceConnection} makes Spring Boot wire the
 * datasource/JPA/Flyway configuration to this container automatically, which is what guarantees that
 * tests never fall back to the {@code DB_URL} placeholder declared in {@code application.yml}.
 */
public abstract class AbstractPostgresIntegrationTest {

	/**
	 * {@code max_connections} of the test container (TASK-012). Every cached Spring test context
	 * keeps its Hikari pool full (10 connections) for as long as the JVM lives: at 100, PostgreSQL's
	 * default, the 9 contexts of today used 90, and caching one more with a pool of 20 made the next
	 * context fail with {@code FATAL: sorry, too many clients already} (TASK-003). 300 keeps the projected worst case of release 0.1.0 (171) under
	 * 80% and leaves room for about 22 cached contexts of 10 plus the transient pool of 20 of {@code
	 * HighlightLimitConcurrencyTest}; beyond that, raise it again or lower {@code minimum-idle}.
	 * Fixed by {@code PostgresTestContainerSettingsTest}.
	 */
	static final int MAX_CONNECTIONS = 300;

	/**
	 * Ephemeral PostgreSQL 16 container backing every integration test that extends this class.
	 * Declared {@code static} so it is created exactly once per JVM and reused across all
	 * subclasses, and annotated with {@link ServiceConnection} so Spring Boot auto-configures the
	 * {@code DataSource} (and, transitively, JPA/Flyway) from it, overriding any placeholder
	 * datasource configuration from {@code application.yml}.
	 *
	 * <p>{@code withCommand} replaces the whole command, including the {@code -c fsync=off} that the
	 * {@link PostgreSQLContainer} constructor sets: it is repeated here on purpose, so raising {@link
	 * #MAX_CONNECTIONS} does not silently turn {@code fsync} back on.
	 */
	@ServiceConnection
	protected static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
					.withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=" + MAX_CONNECTIONS);

	static {
		POSTGRES.start();
	}
}
