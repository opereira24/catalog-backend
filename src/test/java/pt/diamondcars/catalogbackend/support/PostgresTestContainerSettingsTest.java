package pt.diamondcars.catalogbackend.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * Pins the server settings of the shared test container that {@link AbstractPostgresIntegrationTest}
 * starts (TASK-012), read from the running server, not from the code that asks for them.
 *
 * <p>Plain JUnit on purpose: no {@code @SpringBootTest}, so no Spring context and no Hikari pool of
 * its own (it would spend the very connections this test guards). JDBC goes straight to the
 * container, the way {@code V1ToV2UpgradeTest} reaches it without Spring.
 *
 * <p>Mutation evidence (TASK-012, PR): removing {@code "-c", "max_connections=" + MAX_CONNECTIONS}
 * from the container command turns {@link #maxConnectionsIsTheBudgetedLimit()} red (100); removing
 * {@code "-c", "fsync=off"} turns {@link #fsyncStaysOff()} red ({@code on}).
 */
class PostgresTestContainerSettingsTest extends AbstractPostgresIntegrationTest {

	@Test
	void maxConnectionsIsTheBudgetedLimit() throws SQLException {
		assertThat(show("max_connections"))
				.as("max_connections of the test container: every cached Spring context holds 10"
						+ " connections, see TASK-012 / javadoc of AbstractPostgresIntegrationTest#MAX_CONNECTIONS")
				.isEqualTo(String.valueOf(AbstractPostgresIntegrationTest.MAX_CONNECTIONS));
	}

	@Test
	void fsyncStaysOff() throws SQLException {
		assertThat(show("fsync"))
				.as("fsync of the test container: withCommand replaces the constructor's '-c fsync=off',"
						+ " so the command must repeat it, see TASK-012 / javadoc of"
						+ " AbstractPostgresIntegrationTest#POSTGRES")
				.isEqualTo("off");
	}

	private static String show(String setting) throws SQLException {
		try (Connection connection = POSTGRES.createConnection("");
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SHOW " + setting)) {
			assertThat(result.next()).as("SHOW %s returned no row", setting).isTrue();
			return result.getString(1);
		}
	}
}
