package pt.diamondcars.catalogbackend.service;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
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
 * caught, logged at {@code WARN}, and recorded on the lead itself ({@code forwardAttempts}, via
 * {@link LeadForwardOutcomeRecorder}) instead of being rethrown — a slow or down {@code
 * dcbo-backend} must never turn a 201 for the site visitor into anything else.
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
	 */
	public LeadForwarder(
			@Qualifier(LeadForwardingClientConfig.BEAN_NAME) RestClient.Builder restClientBuilder,
			@Value("${internal.sync.token}") String internalToken,
			LeadRepository leadRepository,
			LeadForwardOutcomeRecorder outcomeRecorder) {
		this.restClientBuilder = restClientBuilder;
		this.internalToken = internalToken;
		this.leadRepository = leadRepository;
		this.outcomeRecorder = outcomeRecorder;
	}

	/**
	 * Attempts the forward strictly after the lead's creating transaction commits (requirement 6):
	 * registered as an {@code AFTER_COMMIT} listener so a rollback of the business transaction
	 * never triggers a forward for a lead that was never actually persisted.
	 *
	 * @param event the just-committed lead's id, wrapped
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onLeadCreated(LeadCreatedEvent event) {
		forward(event.leadId());
	}

	/**
	 * Attempts to forward one lead to {@code dcbo-backend}, used both by {@link #onLeadCreated}
	 * above and by {@link LeadForwardScheduler}'s periodic retry.
	 *
	 * @param leadId the id of the lead to forward; silently does nothing if it no longer exists
	 */
	public void forward(UUID leadId) {
		leadRepository.findById(leadId).ifPresent(this::attemptForward);
	}

	/**
	 * Performs the actual HTTP call and records its outcome via {@link #outcomeRecorder}. Never
	 * throws: any failure (timeout, connection refused, non-2xx status) is caught, logged, and
	 * recorded as a failed attempt instead (requirement 6).
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
		} catch (RestClientException exception) {
			log.warn("Failed to forward lead {} to dcbo-backend", lead.getId(), exception);
			outcomeRecorder.incrementAttempts(lead.getId());
		}
	}
}
