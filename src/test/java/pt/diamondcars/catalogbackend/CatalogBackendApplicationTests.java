package pt.diamondcars.catalogbackend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import pt.diamondcars.catalogbackend.service.LeadForwardScheduler;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * Smoke test that verifies the Spring application context starts up successfully against a real,
 * ephemeral PostgreSQL database provided by {@link AbstractPostgresIntegrationTest}.
 */
@SpringBootTest
class CatalogBackendApplicationTests extends AbstractPostgresIntegrationTest {

	@Autowired private ApplicationContext applicationContext;

	/**
	 * Verifies that the full Spring application context loads without errors, with the datasource
	 * wired to the Testcontainers-managed PostgreSQL instance instead of the placeholder configured
	 * in {@code application.yml}.
	 */
	@Test
	void contextLoads() {
	}

	/**
	 * TASK-015 review r1, IMPORTANTE 4: this test class declares no {@code
	 * app.leads.forward.retry.enabled} property of its own — the property forced to {@code false}
	 * on {@link AbstractPostgresIntegrationTest} is the only thing keeping {@link
	 * LeadForwardScheduler} out of this context by default. Before that fix, this context (and
	 * every other one sharing the same Postgres container/test classpath) started with the
	 * scheduler active, free to fire immediately and attempt a real outbound HTTP call.
	 */
	@Test
	void leadForwardSchedulerIsNotRegisteredByDefaultInTests() {
		assertThat(applicationContext.getBeanNamesForType(LeadForwardScheduler.class)).isEmpty();
	}

}
