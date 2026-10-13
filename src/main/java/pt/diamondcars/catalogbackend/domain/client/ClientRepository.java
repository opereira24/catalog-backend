package pt.diamondcars.catalogbackend.domain.client;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Client}. Extends {@link JpaSpecificationExecutor} so the
 * back-office client listing can combine its optional free-text search over name/email/phone/NIF
 * without one derived-query method per field. Plus the row lock of TASK-010 ({@link
 * #findByIdForUpdate}).
 */
public interface ClientRepository
		extends JpaRepository<Client, UUID>, JpaSpecificationExecutor<Client> {

	/**
	 * Loads a client and locks its row until the end of the transaction ({@code FOR NO KEY UPDATE}
	 * on PostgreSQL), TASK-010 AC C.1.b. Used before any change to {@code purchases_count} (sale,
	 * reversal, deletion of a sold car) and by {@code ClientService#update}/{@code #delete}: without
	 * it, two sales to the same buyer, or a sale and an edit of the buyer, lose an increment, and a
	 * client could be deleted between the "has no cars" check and a sale to it.
	 *
	 * <p>Must be the first load of this client in the transaction (same trap as {@code
	 * PartnerRepository#findByIdForUpdate}).
	 *
	 * @param id the client's id
	 * @return the locked client, or empty if no client has this id
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Client c where c.id = :id")
	Optional<Client> findByIdForUpdate(@Param("id") UUID id);
}
