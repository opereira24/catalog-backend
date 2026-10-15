package pt.diamondcars.catalogbackend.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request payload for {@code PATCH /api/backoffice/users/{id}/active} (TASK-007, AC C.4), the
 * single equivalent of {@code deactivateUser}/{@code activateUser} in {@code dcbo}. Ported
 * unchanged from {@code dcbo-backend}.
 *
 * @param active the profile's new state; required ({@code Boolean}, not {@code boolean}, so that
 *     {@code {}} or {@code {"active": null}} is a 400 naming the field instead of defaulting to
 *     {@code false} and deactivating the user)
 */
public record AppUserActiveRequest(@NotNull Boolean active) {}
