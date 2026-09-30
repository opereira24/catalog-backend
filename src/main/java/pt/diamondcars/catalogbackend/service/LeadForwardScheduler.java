package pt.diamondcars.catalogbackend.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron trigger for {@link LeadForwardRetryService#retryPendingForwards()} (TASK-015 requirement
 * 7) — a thin wrapper carrying only the {@link Scheduled} annotation, so the retry logic itself
 * lives in an always-registered bean tests can call directly.
 *
 * <p>Disabled entirely — no bean created at all, not just a no-op run — when {@code
 * app.leads.forward.retry.enabled} is {@code false} (requirement 7: "tem de estar desligado por
 * defeito em testes"). {@code app.leads.forward.retry.enabled} defaults to {@code true} in {@code
 * application.yml} (the correct production default), so every test class forces it back to {@code
 * false} via {@code @TestPropertySource} on {@code
 * pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest} — not a test-only {@code
 * application.yml}, which does not exist (TASK-015 review r1, IMPORTANTE 4: two individual test
 * classes previously set this property themselves, but every other test class sharing the same
 * Postgres container still ran with the scheduler active by default, and was only saved from an
 * actual outbound HTTP attempt by execution-order luck). With the property forced at the shared
 * base class, no test suite can ever again race a spontaneous scheduled run; tests instead invoke
 * {@link LeadForwardRetryService#retryPendingForwards()} directly.
 */
@Component
@ConditionalOnProperty(name = "app.leads.forward.retry.enabled", havingValue = "true", matchIfMissing = true)
public class LeadForwardScheduler {

	private final LeadForwardRetryService retryService;

	/**
	 * Creates the scheduler bound to the service it triggers.
	 *
	 * @param retryService the service whose {@link LeadForwardRetryService#retryPendingForwards()}
	 *     is invoked on every tick
	 */
	public LeadForwardScheduler(LeadForwardRetryService retryService) {
		this.retryService = retryService;
	}

	/**
	 * Triggers {@link LeadForwardRetryService#retryPendingForwards()} on a fixed delay.
	 */
	@Scheduled(fixedDelayString = "${app.leads.forward.retry.interval-ms:300000}")
	public void run() {
		retryService.retryPendingForwards();
	}
}
