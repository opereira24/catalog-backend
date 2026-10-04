package pt.diamondcars.catalogbackend.domain.car;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data repository for {@link Car}: the derived queries the public catalog uses, plus the
 * ones {@code dcbo-backend}'s back-office services call (ported by TASK-001, only the methods
 * with a production caller), plus {@link JpaSpecificationExecutor} so {@code CarQueryService} can
 * compose the optional filters {@code GET /api/cars} accepts without one derived-query method per
 * combination.
 */
public interface CarRepository extends JpaRepository<Car, UUID>, JpaSpecificationExecutor<Car> {

	/**
	 * Lists cars not yet sold, most recently created first — the listing order the public site
	 * uses, equivalent to {@code dc/src/services/firebaseService.js:24-29}.
	 *
	 * @param pageable pagination/sort request
	 * @return a page of cars where {@code vendido = false}, ordered by {@code createdAt} descending
	 */
	Page<Car> findByVendidoFalseOrderByCreatedAtDesc(Pageable pageable);

	/**
	 * Lists cars flagged as highlighted and not yet sold — the set shown on the site's homepage
	 * carousel.
	 *
	 * @return cars where {@code destaque = true} and {@code vendido = false}
	 */
	List<Car> findByDestaqueTrueAndVendidoFalse();

	/**
	 * Lists cars flagged as highlighted and not yet sold, most recently created first with {@code
	 * id} ascending as a tiebreaker, capped by {@code pageable} — backs {@code
	 * GET /api/cars/highlights}, which must never return more than 8 cars.
	 *
	 * <p>The {@code id} tiebreaker makes which 8 cars get selected deterministic even when more
	 * than 8 highlighted, unsold cars share the exact same {@code createdAt} (same rationale as
	 * {@code CarQueryService#DEFAULT_SORT}) — without it, Postgres is free to pick a different
	 * subset of the tied rows on every call.
	 *
	 * @param pageable a pageable requesting at most the desired number of results (e.g. {@code
	 *     PageRequest.of(0, 8)}); only its page size and offset are used, sorting is fixed to
	 *     {@code createdAt} descending then {@code id} ascending
	 * @return at most {@code pageable.getPageSize()} cars where {@code destaque = true} and {@code
	 *     vendido = false}, most recently created first
	 */
	List<Car> findByDestaqueTrueAndVendidoFalseOrderByCreatedAtDescIdAsc(Pageable pageable);

	/**
	 * Counts how many cars are currently featured, the value the back-office compares against the
	 * 8-car limit before allowing one more car to be featured.
	 *
	 * @return the number of cars with {@code destaque = true}
	 */
	long countByDestaqueTrue();

	/**
	 * Lists cars purchased by a given client, most recently created first — backs the back-office
	 * client's cars view ({@code dcbo/src/components/client-cars-modal.js}).
	 *
	 * @param clientId the {@link pt.diamondcars.catalogbackend.domain.client.Client} id to filter by
	 * @return cars whose {@code client_id} equals {@code clientId}, ordered by {@code createdAt}
	 *     descending
	 */
	List<Car> findByClientIdOrderByCreatedAtDesc(UUID clientId);

	/**
	 * Tests whether any car references a given client, used to refuse deleting a client that has
	 * purchased cars instead of deleting it silently.
	 *
	 * @param clientId the {@link pt.diamondcars.catalogbackend.domain.client.Client} id to check
	 * @return {@code true} if at least one car has this {@code client_id}
	 */
	boolean existsByClientId(UUID clientId);

	/**
	 * Lists cars in consignment for a given partner, most recently created first — backs the
	 * back-office partner's cars view.
	 *
	 * @param partnerId the {@link pt.diamondcars.catalogbackend.domain.partner.Partner} id to filter
	 *     by
	 * @return cars whose {@code partner_id} equals {@code partnerId}, ordered by {@code createdAt}
	 *     descending
	 */
	List<Car> findByPartnerIdOrderByCreatedAtDesc(UUID partnerId);

	/**
	 * Tests whether any car references a given partner, used to refuse deleting a partner that has
	 * consignment cars instead of deleting it silently.
	 *
	 * @param partnerId the {@link pt.diamondcars.catalogbackend.domain.partner.Partner} id to check
	 * @return {@code true} if at least one car has this {@code partner_id}
	 */
	boolean existsByPartnerId(UUID partnerId);
}
