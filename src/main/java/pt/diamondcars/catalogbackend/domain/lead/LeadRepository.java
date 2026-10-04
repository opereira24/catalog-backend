package pt.diamondcars.catalogbackend.domain.lead;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data repository for {@link Lead}: the pending-forward query of the forwarding flow, plus
 * {@link JpaSpecificationExecutor} so the back-office lead listing can combine its optional
 * filters (status, car) without one derived-query method per combination.
 */
public interface LeadRepository extends JpaRepository<Lead, UUID>, JpaSpecificationExecutor<Lead> {

	/**
	 * Lists every lead not yet forwarded to {@code dcbo-backend} — the set the periodic retry of
	 * {@code LeadForwardRetryService} works through.
	 *
	 * @return leads whose {@code forwarded_at} is {@code null}
	 */
	List<Lead> findByForwardedAtIsNull();
}
