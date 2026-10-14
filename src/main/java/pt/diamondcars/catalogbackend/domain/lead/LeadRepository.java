package pt.diamondcars.catalogbackend.domain.lead;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Lead}: {@link JpaSpecificationExecutor} so the back-office lead
 * listing can combine its optional filters (status, car) without one derived-query method per
 * combination, plus the row lock of the back-office edit and delete ({@link #findByIdForUpdate}).
 */
public interface LeadRepository extends JpaRepository<Lead, UUID>, JpaSpecificationExecutor<Lead> {

	/**
	 * Loads a lead and locks its row until the end of the transaction ({@code FOR NO KEY UPDATE} on
	 * PostgreSQL), TASK-006 AC E.2: {@code PUT} and {@code DELETE} of {@code /api/backoffice/leads}
	 * read the lead with this, never with {@code findById}. Without it, a {@code PUT} or {@code
	 * DELETE} concurrent with a {@code DELETE} of the same lead deleted or updated 0 rows at flush,
	 * Hibernate threw {@code StaleStateException} and the caller got a 500; with it, the second one
	 * waits for the first to commit and then finds no row (404).
	 *
	 * <p>Must be the first load of this lead in the transaction: if the lead is already in the
	 * persistence context, Hibernate locks the row but hands back the stale state it had in memory.
	 *
	 * @param id the lead's id
	 * @return the locked lead, or empty if no lead has this id
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select l from Lead l where l.id = :id")
	Optional<Lead> findByIdForUpdate(@Param("id") UUID id);
}
