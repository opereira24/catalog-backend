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
 * app.leads.forward.retry.enabled} is {@code false}, the default the test classpath's {@code
 * application.yml} sets (requirement 7: "tem de estar desligado por defeito em testes"), so a
 * test suite never races a spontaneous scheduled run; tests instead invoke {@link
 * LeadForwardRetryService#retryPendingForwards()} directly.
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
