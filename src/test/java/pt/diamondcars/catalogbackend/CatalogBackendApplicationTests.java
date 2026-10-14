package pt.diamondcars.catalogbackend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import pt.diamondcars.catalogbackend.web.RateLimitInterceptor;

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
	 * in {@code application.yml}. Since TASK-006 this context also defines none of the old
	 * forwarding properties ({@code DCBO_SYNC_URL}, {@code CATALOG_SYNC_TOKEN}, {@code
	 * LEADS_FORWARD_*}): starting at all proves the service no longer needs them.
	 */
	@Test
	void contextLoads() {
	}

	/**
	 * TASK-006 AC A.2: {@code @EnableScheduling} on {@link CatalogBackendApplication} stays after the
	 * lead-forwarding scheduler is gone, because {@code RateLimitInterceptor#evictExpiredEntries} is
	 * {@code @Scheduled} and is the only sweep of the rate limiter's per-IP map. Without the
	 * annotation no scheduled-task holder exists and the map grows without bound; this test goes red.
	 * A {@link ScheduledTask} prints as {@code <target class>.<method>}.
	 */
	@Test
	void theRateLimiterSweepIsAScheduledTask() {
		assertThat(applicationContext.getBeansOfType(ScheduledTaskHolder.class).values())
				.flatMap(ScheduledTaskHolder::getScheduledTasks)
				.extracting(ScheduledTask::toString)
				.contains(RateLimitInterceptor.class.getName() + ".evictExpiredEntries");
	}
}
