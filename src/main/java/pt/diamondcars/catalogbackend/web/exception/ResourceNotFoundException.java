package pt.diamondcars.catalogbackend.web.exception;

/**
 * Thrown by a back-office service when a requested resource (a car, a partner, ...) does not
 * exist, mapped to 404 by {@link pt.diamondcars.catalogbackend.web.ApiExceptionHandler} with the
 * exception's message. Generic on purpose: the back-office tasks that follow (clients, partners,
 * transactions, leads, users) reuse it. Ported from {@code dcbo-backend}.
 *
 * <p>The public {@code GET /api/cars/{id}} keeps its own {@link CarNotFoundException} (same message
 * for a car): unifying them would touch frozen public code for no gain.
 */
public class ResourceNotFoundException extends RuntimeException {

	/**
	 * Creates the exception with a human-readable message describing which resource was not found.
	 *
	 * @param message description surfaced in the 404 body's {@code message}, e.g. {@code "Carro nao
	 *     encontrado: <id>"}
	 */
	public ResourceNotFoundException(String message) {
		super(message);
	}
}
