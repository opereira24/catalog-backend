package pt.diamondcars.catalogbackend.web.exception;

/**
 * Thrown when deleting a back-office resource (a client, a partner) is refused because a car
 * still references it (TASK-004) — mapped by {@link
 * pt.diamondcars.catalogbackend.web.ApiExceptionHandler} to a 409 response. Ported from {@code
 * dcbo-backend}'s exception of the same name.
 *
 * <p>Deleting the dependent instead of refusing the request would silently lose data (a car's
 * buyer or consignment partner), which is irreversible; an explicit 409 is not.
 */
public class ResourceInUseException extends RuntimeException {

	/**
	 * Creates the exception with a human-readable message describing the conflict.
	 *
	 * @param message description to surface in the 409 response body's {@code message} field
	 */
	public ResourceInUseException(String message) {
		super(message);
	}
}
