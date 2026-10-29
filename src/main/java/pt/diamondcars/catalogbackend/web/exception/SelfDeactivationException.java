package pt.diamondcars.catalogbackend.web.exception;

/**
 * Thrown when an admin calls {@code PATCH /api/backoffice/users/{id}/active} with {@code active:
 * false} on their own profile (TASK-007, AC C.4) — mapped by {@link
 * pt.diamondcars.catalogbackend.web.ApiExceptionHandler} to 409. Ported from {@code
 * dcbo-backend}'s exception of the same name.
 *
 * <p>Why it is refused: {@link pt.diamondcars.catalogbackend.config.ActiveUserInterceptor} rejects
 * every authenticated request of a {@code sub} whose profile is inactive, this endpoint and {@code
 * /api/backoffice/me} included. An admin who deactivated themselves could only be reactivated by
 * another admin, or by a manual {@code UPDATE app_users SET active = true} if there is none.
 */
public class SelfDeactivationException extends RuntimeException {

	/**
	 * Creates the exception with a human-readable message describing the conflict.
	 *
	 * @param message description to surface in the 409 response body's {@code message} field
	 */
	public SelfDeactivationException(String message) {
		super(message);
	}
}
