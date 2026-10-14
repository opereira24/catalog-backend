package pt.diamondcars.catalogbackend.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for {@code POST /api/backoffice/users} (TASK-007, AC A.2/A.3): registers the
 * local back-office profile of an Auth0 account that already exists in the tenant (this API never
 * creates the Auth0 account). Ported unchanged from {@code dcbo-backend}.
 *
 * <p>No {@code id} (always server-generated) and no {@code active}: a new profile always starts
 * active, like {@code createUser} in {@code dcbo}. Unknown keys, {@code active: false} included,
 * are ignored.
 *
 * @param authSubject the Auth0 {@code sub} of the account; required, unique (409 when taken)
 * @param email the user's email address, required and format-checked
 * @param name full name, 2 to 100 characters
 * @param role {@code "admin"} or {@code "user"}, exactly as stored and as {@code dcbo} writes it;
 *     anything else (including {@code "ADMIN"}) is a 400 here instead of reaching {@link
 *     pt.diamondcars.catalogbackend.domain.user.AppUserRole#fromValue(String)} as an unmapped
 *     {@link IllegalArgumentException}
 */
public record AppUserRequest(
		@NotBlank @Size(max = 255) String authSubject,
		@NotBlank @Email @Size(max = 255) String email,
		@NotBlank @Size(min = 2, max = 100) String name,
		@NotBlank @Pattern(regexp = AppUserRequest.ROLE_REGEXP) String role) {

	/** Every value {@link pt.diamondcars.catalogbackend.domain.user.AppUserRole} accepts. */
	static final String ROLE_REGEXP = "^(admin|user)$";
}
