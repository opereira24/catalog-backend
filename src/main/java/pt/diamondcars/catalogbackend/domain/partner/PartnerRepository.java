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
 * findByPartnerIdOrderByCreatedAtDesc}). Plus the row lock of TASK-010 ({@link #findByIdForUpdate}).
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
}
