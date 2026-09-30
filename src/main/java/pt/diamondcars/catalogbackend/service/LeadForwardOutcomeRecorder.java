package pt.diamondcars.catalogbackend.service;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pt.diamondcars.catalogbackend.domain.lead.LeadRepository;

/**
 * Records the outcome of one {@link LeadForwarder} forwarding attempt, each method in its own,
 * genuinely new database transaction.
 *
 * <p>Kept as a separate bean from {@link LeadForwarder} — not two more methods on that class —
 * for two independent reasons that both matter here:
 *
 * <ol>
 *   <li>A same-instance call from one method of a bean to another ({@code
 *       LeadForwarder.attemptForward} calling a {@code @Transactional} method of {@code
 *       LeadForwarder} itself) bypasses the Spring AOP transaction proxy entirely — the classic
 *       self-invocation pitfall. Only a call that crosses a proxy boundary (i.e. to a different
 *       bean) is actually intercepted and wrapped in a transaction.
 *   <li>{@link #markForwarded(UUID)}/{@link #incrementAttempts(UUID)} are invoked from {@link
 *       LeadForwarder#onLeadCreated(LeadCreatedEvent)}, itself an {@code AFTER_COMMIT} listener of
 *       the lead's own creating transaction. At that point in Spring's commit sequence, the
 *       original transaction's {@code EntityManager} may still be thread-bound (it is only
 *       unbound in the later {@code afterCompletion} step) — {@link Propagation#REQUIRES_NEW}
 *       forces a genuinely new, independent transaction and {@code EntityManager} regardless, so
 *       the update is not silently attempted against a session that is already on its way out.
 * </ol>
 */
@Service
public class LeadForwardOutcomeRecorder {

	/**
	 * Sentinel {@code forwardAttempts} value {@link #markPermanentlyFailed(UUID)} sets to take a
	 * lead out of {@code LeadForwardRetryService}'s retry pool forever (TASK-015 review r1,
	 * BLOQUEADOR 1), without needing a dedicated column/migration: any {@code
	 * app.leads.forward.max-attempts} configured value is always smaller than this, so {@code
	 * forwardAttempts < maxAttempts} is permanently {@code false} for that lead.
	 */
	static final int PERMANENT_FAILURE_ATTEMPTS = Integer.MAX_VALUE;

	private final LeadRepository leadRepository;

	/**
	 * Creates the recorder with its backing repository.
	 *
	 * @param leadRepository the repository used to reload the lead and persist the outcome
	 */
	public LeadForwardOutcomeRecorder(LeadRepository leadRepository) {
		this.leadRepository = leadRepository;
	}

	/**
	 * Marks a lead as successfully forwarded, in a new transaction.
	 *
	 * @param leadId the forwarded lead's id
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markForwarded(UUID leadId) {
		leadRepository.findById(leadId).ifPresent(lead -> lead.setForwardedAt(OffsetDateTime.now()));
	}

	/**
	 * Records a failed, but potentially still-transient, forwarding attempt, in a new transaction.
	 *
	 * @param leadId the lead whose forward attempt failed
	 * @return the lead's {@code forwardAttempts} after this increment, or {@code 0} if the lead no
	 *     longer exists (so a caller can decide whether the ceiling was just reached)
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int incrementAttempts(UUID leadId) {
		return leadRepository
				.findById(leadId)
				.map(
						lead -> {
							int updatedAttempts = lead.getForwardAttempts() + 1;
							lead.setForwardAttempts(updatedAttempts);
							return updatedAttempts;
						})
				.orElse(0);
	}

	/**
	 * Marks a lead's forwarding as permanently failed, in a new transaction (TASK-015 review r1,
	 * BLOQUEADOR 1): used when {@code dcbo-backend} rejects the forward with 400, meaning the
	 * payload itself is invalid and retrying the exact same payload would only ever fail the same
	 * way. Sets {@code forwardAttempts} to {@link #PERMANENT_FAILURE_ATTEMPTS} rather than
	 * incrementing it, so {@code LeadForwardRetryService} skips this lead on every future run
	 * without needing its own "permanently failed" column.
	 *
	 * @param leadId the lead whose forward attempt was permanently rejected
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markPermanentlyFailed(UUID leadId) {
		leadRepository.findById(leadId).ifPresent(lead -> lead.setForwardAttempts(PERMANENT_FAILURE_ATTEMPTS));
	}
}
