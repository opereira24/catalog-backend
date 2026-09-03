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
	 * Ephemeral PostgreSQL 16 container backing every integration test that extends this class.
	 * Declared {@code static} so it is created exactly once per JVM and reused across all
	 * subclasses, and annotated with {@link ServiceConnection} so Spring Boot auto-configures the
	 * {@code DataSource} (and, transitively, JPA/Flyway) from it, overriding any placeholder
	 * datasource configuration from {@code application.yml}.
	 */
	@ServiceConnection
	protected static final PostgreSQLContainer POSTGRES =
			new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));

	static {
		POSTGRES.start();
	}
}
