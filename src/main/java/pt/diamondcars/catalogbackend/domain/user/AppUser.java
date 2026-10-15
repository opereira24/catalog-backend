package pt.diamondcars.catalogbackend.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import pt.diamondcars.catalogbackend.domain.support.AbstractAuditableDomainEntity;

/**
 * A back-office user account mapped over the {@code app_users} table of {@code
 * V2__unified_back_office_schema.sql} (named {@code app_users}, not {@code users}, to avoid the
 * reserved SQL keyword). Ported unchanged from {@code dcbo-backend} (TASK-001).
 *
 * <p>{@link #authSubject} is the Auth0 {@code sub} claim that replaces the old Firebase Auth
 * identifier (equivalent to {@code getUserByAuthId} in {@code
 * dcbo/src/services/firebaseService.js:667}); it is enforced unique at the database level ({@code
 * idx_app_users_auth_subject}) and mirrored here with {@code unique = true} for
 * self-documentation.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(of = {"email", "name", "role", "active"})
@Entity
@Table(name = "app_users")
public class AppUser extends AbstractAuditableDomainEntity {

	@Column(name = "auth_subject", nullable = false, unique = true, length = 255)
	private String authSubject;

	@Column(name = "email", nullable = false, length = 255)
	private String email;

	@Column(name = "name", nullable = false, length = 255)
	private String name;

	@Column(name = "role", nullable = false, length = 50)
	private AppUserRole role;

	@Builder.Default
	@Column(name = "active", nullable = false)
	private boolean active = true;
}
