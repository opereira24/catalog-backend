package pt.diamondcars.catalogbackend.domain.car;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data repository for {@link Car}, with the derived queries TASK-013 requirement 8 lists
 * as needed by the public site's listing pages, plus {@link JpaSpecificationExecutor} so
 * TASK-014's {@code CarQueryService} can compose the optional filters {@code GET /api/cars}
 * accepts (brand, fuel, price/year range, featured, sold) without one derived-query method per
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
	 * GET /api/cars/highlights} (TASK-014 requirement 3), which must never return more than 8 cars.
	 *
	 * <p>The {@code id} tiebreaker makes which 8 cars get selected deterministic even when more
	 * than 8 highlighted, unsold cars share the exact same {@code createdAt} (same rationale as
	 * {@code CarQueryService#DEFAULT_SORT}; fixed per TASK-014 review r1, SUGESTÃO 1) — without it,
	 * Postgres is free to pick a different subset of the tied rows on every call.
	 *
	 * @param pageable a pageable requesting at most the desired number of results (e.g. {@code
	 *     PageRequest.of(0, 8)}); only its page size and offset are used, sorting is fixed to
	 *     {@code createdAt} descending then {@code id} ascending
	 * @return at most {@code pageable.getPageSize()} cars where {@code destaque = true} and {@code
	 *     vendido = false}, most recently created first
	 */
	List<Car> findByDestaqueTrueAndVendidoFalseOrderByCreatedAtDescIdAsc(Pageable pageable);
}
