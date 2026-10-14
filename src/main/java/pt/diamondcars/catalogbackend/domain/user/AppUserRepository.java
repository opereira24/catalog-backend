package pt.diamondcars.catalogbackend.domain.user;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link AppUser}, ported from {@code dcbo-backend} (TASK-001), plus the
 * row lock of the profile writes ({@link #findByIdForUpdate}, TASK-007).
 */
public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

	/**
	 * Finds the back-office user whose Auth0 subject matches the given value, equivalent to {@code
	 * getUserByAuthId} in {@code dcbo/src/services/firebaseService.js:667}. Used by the resource
	 * server security layer to resolve the authenticated principal.
	 *
	 * @param authSubject the Auth0 {@code sub} claim to look up
	 * @return the matching user, or empty if no user has that {@code auth_subject}
	 */
	Optional<AppUser> findByAuthSubject(String authSubject);

	/**
	 * Loads a profile and locks its row until the end of the transaction ({@code FOR NO KEY UPDATE}
	 * on PostgreSQL), TASK-007 AC C.6: {@code AppUserService#update}, {@code #setActive} and {@code
	 * #delete} read the profile with this, never with {@code findById}.
	 *
	 * <p>Why: Hibernate writes the whole row with the values it read. Without the lock, a name edit
	 * read before another admin's deactivation committed wrote {@code active = true} back, and the
	 * deactivated user got their access back in silence; and an edit or a deletion of a profile being
	 * deleted touched 0 rows at flush ({@code StaleStateException}, a 500). With it, the second write
	 * waits for the first and sees its result, or 404 ({@code service/AppUserConcurrencyTest}).
	 *
	 * <p>Must be the first load of this profile in the transaction: if it is already in the
	 * persistence context, Hibernate locks the row but hands back the stale state it had in memory.
	 *
	 * @param id the profile's id
	 * @return the locked profile, or empty if no profile has this id
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from AppUser u where u.id = :id")
	Optional<AppUser> findByIdForUpdate(@Param("id") UUID id);
}
