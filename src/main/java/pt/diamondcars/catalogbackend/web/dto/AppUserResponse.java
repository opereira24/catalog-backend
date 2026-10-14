package pt.diamondcars.catalogbackend.web.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import pt.diamondcars.catalogbackend.domain.user.AppUser;

/**
 * Response payload for every {@code /api/backoffice/users} endpoint and {@code GET
 * /api/backoffice/me} (TASK-007, AC A.3): exactly these 8 keys. Never the JPA entity itself.
 * Ported unchanged from {@code dcbo-backend}.
 *
 * @param id the profile's identifier
 * @param authSubject the Auth0 {@code sub} this profile is bound to
 * @param email the user's email address
 * @param name full name
 * @param role {@code "admin"} or {@code "user"} ({@link
 *     pt.diamondcars.catalogbackend.domain.user.AppUserRole#getValue()}); informative, the role that
 *     authorizes is the one in the token (AC B.3)
 * @param active whether this profile may use the API ({@link
 *     pt.diamondcars.catalogbackend.config.ActiveUserInterceptor})
 * @param createdAt creation timestamp
 * @param updatedAt last-update timestamp
 */
public record AppUserResponse(
		UUID id,
		String authSubject,
		String email,
		String name,
		String role,
		boolean active,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	/**
	 * Builds the response for a given, fully-loaded {@link AppUser}.
	 *
	 * @param appUser the profile to map, never {@code null}
	 * @return the corresponding response DTO
	 */
	public static AppUserResponse from(AppUser appUser) {
		return new AppUserResponse(
				appUser.getId(),
				appUser.getAuthSubject(),
				appUser.getEmail(),
				appUser.getName(),
				appUser.getRole().getValue(),
				appUser.isActive(),
				appUser.getCreatedAt(),
				appUser.getUpdatedAt());
	}
}
