package pt.diamondcars.catalogbackend.web.exception;

/**
 * Thrown when an admin calls {@code DELETE /api/backoffice/users/{id}} on their own profile
 * (TASK-007, AC C.5) — mapped by {@link pt.diamondcars.catalogbackend.web.ApiExceptionHandler} to
 * 409. Ported from {@code dcbo-backend}'s exception of the same name.
 *
 * <p>Unlike {@link SelfDeactivationException} this is not about a lockout: a {@code sub} without a
 * local profile passes {@link pt.diamondcars.catalogbackend.config.ActiveUserInterceptor} and keeps
 * whatever its token role grants. It is parity with {@code dcbo} ({@code pages/users.js}) and
 * protection against an admin erasing their own profile by mistake.
 */
public class SelfDeletionException extends RuntimeException {

	/**
	 * Creates the exception with a human-readable message describing the conflict.
	 *
	 * @param message description to surface in the 409 response body's {@code message} field
	 */
	public SelfDeletionException(String message) {
		super(message);
	}
}
