package pt.diamondcars.catalogbackend.web.exception;

/**
 * Thrown by {@link pt.diamondcars.catalogbackend.config.ActiveUserInterceptor} when the
 * authenticated caller's local {@code app_users} profile has {@code active = false} (TASK-002,
 * requirement 5), mapped by {@link pt.diamondcars.catalogbackend.web.ApiExceptionHandler} to 403
 * in the same {@code ApiError} envelope as every other mapped exception. Ported from {@code
 * dcbo-backend}.
 */
public class InactiveUserException extends RuntimeException {

	/**
	 * Creates the exception with a human-readable message.
	 *
	 * @param message description to surface in the 403 response body's {@code message} field
	 */
	public InactiveUserException(String message) {
		super(message);
	}
}
