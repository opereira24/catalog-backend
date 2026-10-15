package pt.diamondcars.catalogbackend.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * {@code @DataJpaTest} for the {@link AppUser} aggregate: confirms it round-trips through the real
 * {@code app_users} table, that the {@link AppUserRole} converter
 * survives a save/reload cycle that actually hits the database, and exercises {@link
 * AppUserRepository#findByAuthSubject(String)} (ported from {@code dcbo-backend}).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AppUserRepositoryTest extends AbstractPostgresIntegrationTest {

	@Autowired
	private AppUserRepository appUserRepository;

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * Confirms an {@link AppUser} saved with a {@link AppUserRole} enum value can be reloaded with
	 * that same enum constant intact, and with its database-matching default ({@code active =
	 * true}).
	 *
	 * <p>Uses {@code saveAndFlush} and then clears the persistence context before reloading: a
	 * plain {@code save} followed by {@code findById} inside the same transaction would be served
	 * entirely by Hibernate's first-level cache — no {@code INSERT} or {@code SELECT} would ever
	 * reach the database, and the assertions below would pass regardless of whether the mapping or
	 * the enum converter are even correct.
	 *
	 * @throws AssertionError if the reloaded user does not match what was saved
	 */
	@Test
	void savesAndReloadsAnAppUserWithItsRole() {
		String authSubject = "auth0|" + UUID.randomUUID();

		AppUser saved = appUserRepository.saveAndFlush(AppUser.builder()
				.authSubject(authSubject)
				.email("admin@diamondcars.pt")
				.name("Admin User")
				.role(AppUserRole.ADMIN)
				.build());
		entityManager.clear();

		AppUser reloaded = appUserRepository.findById(saved.getId()).orElseThrow();

		assertThat(reloaded).isNotSameAs(saved);
		assertThat(reloaded.getRole()).isEqualTo(AppUserRole.ADMIN);
		assertThat(reloaded.isActive()).isTrue();
	}

	/**
	 * Confirms {@link AppUserRepository#findByAuthSubject(String)} finds a user by their Auth0
	 * subject and returns empty for a subject that was never persisted.
	 *
	 * @throws AssertionError if either lookup returns the wrong result
	 */
	@Test
	void findsAUserByAuthSubject() {
		String authSubject = "auth0|" + UUID.randomUUID();
		appUserRepository.save(AppUser.builder()
				.authSubject(authSubject)
				.email("user@diamondcars.pt")
				.name("Regular User")
				.role(AppUserRole.USER)
				.build());

		assertThat(appUserRepository.findByAuthSubject(authSubject))
				.isPresent()
				.get()
				.extracting(AppUser::getEmail)
				.isEqualTo("user@diamondcars.pt");

		assertThat(appUserRepository.findByAuthSubject("auth0|does-not-exist")).isEmpty();
	}

	/**
	 * TASK-007 AC F.1: {@code auth_subject} is unique in the database itself ({@code
	 * idx_app_users_auth_subject}), not only by the lookup in {@code AppUserService#create}: that
	 * lookup cannot see a concurrent, uncommitted creation (K-U4 of {@code AppUserConcurrencyTest}).
	 */
	@Test
	void authSubjectIsUniqueInTheDatabase() {
		String authSubject = "auth0|" + UUID.randomUUID();
		appUserRepository.saveAndFlush(AppUser.builder()
				.authSubject(authSubject)
				.email("primeiro@diamondcars.pt")
				.name("Primeiro")
				.role(AppUserRole.USER)
				.build());

		AppUser duplicate = AppUser.builder()
				.authSubject(authSubject)
				.email("segundo@diamondcars.pt")
				.name("Segundo")
				.role(AppUserRole.ADMIN)
				.build();

		assertThatThrownBy(() -> appUserRepository.saveAndFlush(duplicate))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("idx_app_users_auth_subject");
	}
}
