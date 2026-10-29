package pt.diamondcars.catalogbackend.domain.partner;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Partner}. No derived queries beyond CRUD: the partner-related
 * lookups the back-office needs live on {@code CarRepository} ({@code existsByPartnerId}, {@code
 * findByPartnerIdOrderByCreatedAtDesc}). Plus two row locks: {@code FOR NO KEY UPDATE} of TASK-010
 * ({@link #findByIdForUpdate}) and {@code FOR UPDATE} of TASK-015 ({@link #findByIdForDelete}).
 */
public interface PartnerRepository extends JpaRepository<Partner, UUID> {

	/**
	 * Loads a partner and locks its row until the end of the transaction ({@code FOR NO KEY UPDATE}
	 * on PostgreSQL), TASK-010 AC C.1.b. Used before any change to {@code cars_count}/{@code
	 * total_commission} (sale, reversal, edit of a sold consignment car) and by {@code
	 * PartnerService#update}: the counters live on a row that cars with nothing else in common share,
	 * and Hibernate writes the whole row with the values it read, so an unlocked read-modify-write
	 * loses a concurrent increment or a concurrent rename.
	 *
	 * <p>Must be the first load of this partner in the transaction. Reading only {@code
	 * car.getPartner().getId()} (an uninitialised {@code LAZY} proxy) before it is safe; initialising
	 * the proxy before it makes Hibernate lock the row but keep the stale state it had in memory.
	 *
	 * @param id the partner's id
	 * @return the locked partner, or empty if no partner has this id
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Partner p where p.id = :id")
	Optional<Partner> findByIdForUpdate(@Param("id") UUID id);

	/**
	 * Loads a partner and locks its row with {@code FOR UPDATE} until the end of the transaction
	 * (TASK-015). Only {@code PartnerService#delete} uses it, as its first statement, before checking
	 * that no car references the partner.
	 *
	 * <p>Why {@code FOR UPDATE} and not {@link #findByIdForUpdate}: saving a car with {@code
	 * partner_id} = this partner ({@code INSERT} of a {@code POST}, {@code UPDATE} of a {@code PUT})
	 * makes PostgreSQL take {@code FOR KEY SHARE} on this row (foreign key check) until that
	 * transaction commits. Of the row lock modes only {@code FOR UPDATE} conflicts with {@code FOR KEY
	 * SHARE}; {@code FOR NO KEY UPDATE} and {@code FOR SHARE} do not (measured on PostgreSQL 16). With
	 * it first, either the car commits first and the delete waits for it and then sees it (409), or
	 * the delete locks first and the car waits and fails its foreign key check once the delete
	 * commits. Without it both succeed and {@code ON DELETE SET NULL} silently strips the partner from
	 * the car that was just saved.
	 *
	 * <p>Native query because in Hibernate (Boot 4.1.1) no {@code LockModeType} renders {@code FOR
	 * UPDATE} on PostgreSQL ({@code PESSIMISTIC_WRITE} is {@code for no key update}). Not the general
	 * partner lock: it also blocks the foreign key checks of anything that merely references the
	 * partner (sales, transactions); counter updates keep using {@link #findByIdForUpdate}.
	 *
	 * @param id the partner's id
	 * @return the locked partner, or empty if no partner has this id
	 */
	@Query(value = "SELECT p.* FROM partners p WHERE p.id = :id FOR UPDATE", nativeQuery = true)
	Optional<Partner> findByIdForDelete(@Param("id") UUID id);
}
