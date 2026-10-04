package pt.diamondcars.catalogbackend.domain.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link AppUser}, ported unchanged from {@code dcbo-backend}
 * (TASK-001).
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
}
