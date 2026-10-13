package pt.diamondcars.catalogbackend.domain.car;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Car}: the derived queries the public catalog uses, plus the
 * ones {@code dcbo-backend}'s back-office services call (ported by TASK-001, only the methods
 * with a production caller), plus {@link JpaSpecificationExecutor} so {@code CarQueryService} can
 * compose the optional filters {@code GET /api/cars} accepts without one derived-query method per
 * combination, plus the advisory lock the back-office highlight limit takes ({@link
 * #lockHighlightSlots()}, TASK-003), plus the row locks of TASK-010 ({@link #findByIdForUpdate},
 * {@link #lockKeyShare}).
 */
public interface CarRepository extends JpaRepository<Car, UUID>, JpaSpecificationExecutor<Car> {

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

	/**
	 * Takes the transaction-scoped PostgreSQL advisory lock that serialises "turn a car's highlight
	 * on" (TASK-003, AC E.2). Must be called inside a transaction, before {@link
	 * #countByDestaqueTrue()} and before any write of that transaction; PostgreSQL releases it on
	 * commit or rollback.
	 *
	 * <p>Without it, two requests that each count 7 featured cars both feature one more (count, then
	 * save): measured 10 featured cars with 3 concurrent requests, and 9 out of 9 from zero. An
	 * optimistic {@code @Version} on {@code Car} does not help (different rows), and {@code SELECT
	 * ... FOR UPDATE} of the featured cars fails from zero (no row to lock). With this lock, the count after it runs in
	 * a new {@code READ COMMITTED} snapshot and sees the highlight the previous holder committed.
	 *
	 * <p>Two {@code int4} keys on purpose: that key space never collides with the single {@code
	 * bigint} key Flyway locks at startup.
	 *
	 * @return always 1 (a native query has to return something; the lock is the point)
	 */
	@Query(
			value = "SELECT 1 FROM pg_advisory_xact_lock(hashtext('catalog-backend'), hashtext('cars.destaque'))",
			nativeQuery = true)
	int lockHighlightSlots();

	/**
	 * Loads a car and locks its row until the end of the transaction (TASK-010, AC C.1.a): every
	 * back-office operation that writes to an existing car ({@code update}, {@code delete}, {@code
	 * sell}, {@code revertSale}, {@code reserve}, {@code releaseReservation}, {@code highlight})
	 * reads it with this, never with {@code findById}. Hibernate writes the whole row with the values
	 * it read (no {@code @DynamicUpdate}, no {@code @Version}), so two unlocked read-modify-writes of
	 * the same car lose one of them: a concurrent {@code reserve} used to erase a sale.
	 *
	 * <p>On PostgreSQL this is {@code SELECT ... FOR NO KEY UPDATE}, which does not conflict with the
	 * {@code FOR KEY SHARE} of a foreign key check: saving a lead or a manual transaction for this car
	 * never waits for a sale or an edit, only for a {@code DELETE} of the car.
	 *
	 * <p>Must be the first load of this car in the transaction: if the car is already in the
	 * persistence context, Hibernate locks the row but hands back the stale state it had in memory.
	 *
	 * @param id the car's id
	 * @return the locked car, or empty if no car has this id
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Car c where c.id = :id")
	Optional<Car> findByIdForUpdate(@Param("id") UUID id);

	/**
	 * Takes the weakest row lock that blocks a {@code DELETE} of the car ({@code FOR KEY SHARE}), for
	 * {@code POST /api/leads} (TASK-010, AC C.8). Without it, a car deleted between the lead's lookup
	 * and its {@code INSERT} made the insert fail on {@code leads_car_id_fkey} and the lead was lost
	 * with a 409. With it, a lead that arrives during an uncommitted {@code DELETE} waits for the
	 * commit and then finds no car (saved with {@code car_id} null); a {@code DELETE} that arrives
	 * after the lead waits for the lead's commit, and {@code ON DELETE SET NULL} then clears it. It
	 * does not wait for sales, edits or reservations of the car ({@code FOR NO KEY UPDATE}).
	 *
	 * @param id the car's id
	 * @return the id if the car exists (now protected from deletion until the end of the
	 *     transaction), or empty
	 */
	@Query(value = "SELECT id FROM cars WHERE id = :id FOR KEY SHARE", nativeQuery = true)
	Optional<UUID> lockKeyShare(@Param("id") UUID id);
}
