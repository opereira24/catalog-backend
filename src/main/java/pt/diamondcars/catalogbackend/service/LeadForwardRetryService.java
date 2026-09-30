package pt.diamondcars.catalogbackend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.lead.LeadRepository;

/**
 * Retries forwarding every lead still unforwarded to {@code dcbo-backend} (TASK-015 requirement
 * 7): the actual business logic behind {@link LeadForwardScheduler}'s cron trigger, kept in its
 * own always-registered bean (unlike the scheduler itself) so tests can invoke {@link
 * #retryPendingForwards()} directly and deterministically, without depending on whichever {@code
 * app.leads.forward.retry.enabled} value is active.
 */
@Service
public class LeadForwardRetryService {

	private final LeadRepository leadRepository;
	private final LeadForwarder leadForwarder;
	private final int maxAttempts;

	/**
	 * Creates the service with its dependencies and configured retry ceiling.
	 *
	 * @param leadRepository the repository queried for unforwarded leads
	 * @param leadForwarder the forwarder reused to retry each one
	 * @param maxAttempts leads with {@code forwardAttempts} at or above this value are skipped, so
	 *     a permanently-failing forward (e.g. a lead {@code dcbo-backend} always rejects) does not
	 *     retry forever
	 */
	public LeadForwardRetryService(
			LeadRepository leadRepository,
			LeadForwarder leadForwarder,
			@Value("${app.leads.forward.max-attempts:5}") int maxAttempts) {
		this.leadRepository = leadRepository;
		this.leadForwarder = leadForwarder;
		this.maxAttempts = maxAttempts;
	}

	/**
	 * Retries every lead whose {@code forwardedAt} is still {@code null} and whose {@code
	 * forwardAttempts} has not yet reached {@link #maxAttempts} (requirement 7).
	 */
	public void retryPendingForwards() {
		for (Lead lead : leadRepository.findByForwardedAtIsNull()) {
			if (lead.getForwardAttempts() < maxAttempts) {
				leadForwarder.forward(lead.getId());
			}
		}
	}
}
