package pt.diamondcars.catalogbackend.domain.lead;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link Lead}, with the derived query TASK-013 requirement 8 lists as
 * needed by the pending-forward reconciliation flow.
 */
public interface LeadRepository extends JpaRepository<Lead, UUID> {

	/**
	 * Lists every lead not yet forwarded to {@code dcbo-backend} — the set the reconciliation flow
	 * of the follow-up task retries.
	 *
	 * @return leads whose {@code forwarded_at} is {@code null}
	 */
	List<Lead> findByForwardedAtIsNull();
}
