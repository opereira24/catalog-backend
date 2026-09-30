package pt.diamondcars.catalogbackend.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import pt.diamondcars.catalogbackend.config.LeadForwardAsyncConfig;
import pt.diamondcars.catalogbackend.config.LeadForwardingClientConfig;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.lead.LeadRepository;

/**
 * Best-effort forwarder of {@code Lead}s to {@code dcbo-backend}'s {@code POST /internal/leads}
 * (TASK-015 requirements 5-7): listens for {@link LeadCreatedEvent} to attempt an immediate
 * forward strictly after the lead's own transaction commits, and exposes {@link #forward(UUID)}
 * again for {@link LeadForwardScheduler}'s periodic retry of anything still unforwarded.
 *
 * <p>Never lets a forwarding failure propagate to its caller (requirement 6): every exception is
 * caught, logged, and recorded on the lead itself (via {@link LeadForwardOutcomeRecorder})
 * instead of being rethrown — a slow or down {@code dcbo-backend} must never turn a 201 for the
 * site visitor into anything else. Two refinements from TASK-015 review r1:
 *
 * <ul>
 *   <li><b>BLOQUEADOR 1</b>: a 400 response is treated as a permanent rejection ({@link
 *       #attemptForward(Lead)} below), never retried — {@code dcbo-backend} rejecting the exact
 *       same payload a second, third, ... time would only ever fail the same way, and counting it
 *       as one more transient attempt just delays discovering that for no benefit. ASSUNCAO: only
 *       {@code 400} is treated this way, not every 4xx — a {@code 401} (e.g. a misconfigured
 *       token) or {@code 404}/{@code 409} could still resolve once the underlying cause is fixed,
 *       so those stay in the ordinary transient-retry path.
 *   <li><b>IMPORTANTE 1</b>: the attempt that pushes {@code forwardAttempts} to the configured
 *       ceiling logs at {@code ERROR}, not {@code WARN} — before this, a lead abandoned after
 *       {@code app.leads.forward.max-attempts} failed retries (e.g. ~20 minutes of {@code
 *       dcbo-backend} downtime, at the default 5 attempts / 5-minute interval) left no trace
 *       louder than the same {@code WARN} every other transient failure already logs.
 * </ul>
 *
 * <p><b>IMPORTANTE 5</b>: {@link #onLeadCreated(LeadCreatedEvent)} is {@code @Async} (see {@link
 * LeadForwardAsyncConfig}), so the actual HTTP call never runs on the site visitor's own request
 * thread — before this, a slow or hanging {@code dcbo-backend} could keep the visitor waiting up
 * to the full connect/read timeout before the 201 response was written.
 */
@Component
public class LeadForwarder {

	private static final Logger log = LoggerFactory.getLogger(LeadForwarder.class);

	private static final String INTERNAL_LEADS_PATH = "/internal/leads";

	/**
	 * Header {@code dcbo-backend}'s {@code InternalTokenFilter} expects — the same header name
	 * TASK-016 uses on the receiving side of the opposite sync direction.
	 */
	private static final String TOKEN_HEADER = "X-Internal-Token";

	private final RestClient.Builder restClientBuilder;
	private final String internalToken;
	private final LeadRepository leadRepository;
	private final LeadForwardOutcomeRecorder outcomeRecorder;
	private final int maxAttempts;

	/**
	 * Creates the forwarder with its HTTP client and dependencies.
	 *
	 * @param restClientBuilder the qualified builder configured by {@link
	 *     LeadForwardingClientConfig}
	 * @param internalToken the shared secret sent as {@value #TOKEN_HEADER}, reused from {@code
	 *     internal.sync.token} (requirement 5 — the same {@code CATALOG_SYNC_TOKEN} authenticates
	 *     both sync directions between the two backends)
	 * @param leadRepository the repository used to reload the lead before forwarding it
	 * @param outcomeRecorder records the outcome of each attempt in its own, separate transaction
	 *     (see that class's Javadoc for why it must be a different bean from this one)
	 * @param maxAttempts the same ceiling {@code LeadForwardRetryService} enforces, reused only to
	 *     decide when a just-recorded transient failure deserves an {@code ERROR}-level log
	 *     instead of {@code WARN} (review r1, IMPORTANTE 1)
	 */
	public LeadForwarder(
			@Qualifier(LeadForwardingClientConfig.BEAN_NAME) RestClient.Builder restClientBuilder,
			@Value("${internal.sync.token}") String internalToken,
			LeadRepository leadRepository,
			LeadForwardOutcomeRecorder outcomeRecorder,
			@Value("${app.leads.forward.max-attempts:5}") int maxAttempts) {
		this.restClientBuilder = restClientBuilder;
		this.internalToken = internalToken;
		this.leadRepository = leadRepository;
		this.outcomeRecorder = outcomeRecorder;
		this.maxAttempts = maxAttempts;
	}

	/**
	 * Attempts the forward strictly after the lead's creating transaction commits (requirement 6):
	 * registered as an {@code AFTER_COMMIT} listener so a rollback of the business transaction
	 * never triggers a forward for a lead that was never actually persisted. Dispatched on {@link
	 * LeadForwardAsyncConfig}'s dedicated executor (review r1, IMPORTANTE 5) so the site visitor's
	 * own request thread never waits on it.
	 *
	 * @param event the just-committed lead's id, wrapped
	 */
	@Async(LeadForwardAsyncConfig.BEAN_NAME)
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onLeadCreated(LeadCreatedEvent event) {
		forward(event.leadId());
	}

	/**
	 * Attempts to forward one lead to {@code dcbo-backend}, used both by {@link #onLeadCreated}
	 * above and by {@link LeadForwardScheduler}'s periodic retry. Unlike {@link #onLeadCreated},
	 * this runs synchronously on the caller's thread — always the scheduler's own background
	 * thread, never a site visitor's request thread, so there is nothing to gain from dispatching
	 * it asynchronously too.
	 *
	 * @param leadId the id of the lead to forward; silently does nothing if it no longer exists
	 */
	public void forward(UUID leadId) {
		leadRepository.findById(leadId).ifPresent(this::attemptForward);
	}

	/**
	 * Performs the actual HTTP call and records its outcome via {@link #outcomeRecorder}. Never
	 * throws: any failure (timeout, connection refused, non-2xx status) is caught, logged, and
	 * recorded instead (requirement 6). A {@code 400} is recorded as permanent ({@link
	 * LeadForwardOutcomeRecorder#markPermanentlyFailed(UUID)}); every other failure is recorded as
	 * one more transient attempt (review r1, BLOQUEADOR 1/IMPORTANTE 1).
	 *
	 * @param lead the lead to forward
	 */
	private void attemptForward(Lead lead) {
		try {
			restClientBuilder
					.build()
					.post()
					.uri(INTERNAL_LEADS_PATH)
					.header(TOKEN_HEADER, internalToken)
					.body(LeadForwardPayload.from(lead))
					.retrieve()
					.toBodilessEntity();
			outcomeRecorder.markForwarded(lead.getId());
		} catch (HttpClientErrorException.BadRequest exception) {
			log.error(
					"dcbo-backend rejected lead {} with 400 (invalid payload) - treating as a permanent"
							+ " failure, it will not be retried: {}",
					lead.getId(),
					exception.getMessage());
			outcomeRecorder.markPermanentlyFailed(lead.getId());
		} catch (RestClientException exception) {
			int attempts = outcomeRecorder.incrementAttempts(lead.getId());
			if (attempts >= maxAttempts) {
				log.error(
						"Lead {} abandoned after {} failed forwarding attempts (max-attempts={}) - "
								+ "dcbo-backend may be unreachable or misconfigured, manual reconciliation needed",
						lead.getId(),
						attempts,
						maxAttempts,
						exception);
			} else {
				log.warn(
						"Failed to forward lead {} to dcbo-backend (attempt {}/{})",
						lead.getId(),
						attempts,
						maxAttempts,
						exception);
			}
		}
	}
}
