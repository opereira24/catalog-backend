package pt.diamondcars.catalogbackend.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for {@code PUT /api/backoffice/users/{id}} (TASK-007, AC C.3): replaces a
 * profile's name and role, the two fields {@code dcbo} ({@code pages/users.js}) submits on edit.
 * Ported unchanged from {@code dcbo-backend}.
 *
 * <p>Never {@code authSubject} or {@code email} (re-pointing a profile at another Auth0 account
 * would hand one person's history to another), never {@code active} (it has its own endpoint,
 * {@code PATCH .../active}). Those keys in the body are ignored.
 *
 * <p>The role written here is informative only: authorization always comes from the role in the
 * Auth0 token (AC B.3), so changing it does not grant or revoke anything in this API.
 *
 * @param name full name, 2 to 100 characters
 * @param role {@code "admin"} or {@code "user"}; anything else is a 400
 */
public record AppUserUpdateRequest(
		@NotBlank @Size(min = 2, max = 100) String name,
		@NotBlank @Pattern(regexp = AppUserRequest.ROLE_REGEXP) String role) {}
