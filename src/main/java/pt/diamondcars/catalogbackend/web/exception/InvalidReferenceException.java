package pt.diamondcars.catalogbackend.web.exception;

/**
 * Thrown when a request body references another resource by id that does not exist: today only
 * {@code carroId} of {@code POST /api/backoffice/transactions} ({@code carroId: carro nao
 * encontrado: <id>}, TASK-005 AC B.4). Mapped to 400 by {@link
 * pt.diamondcars.catalogbackend.web.ApiExceptionHandler}. Ported from {@code dcbo-backend}.
 *
 * <p>400 and not 404 (unlike {@code partnerId} of {@code CarRequest}, which answers 404): the id is
 * a field of the body, not the resource the URL names, and 400 is what the {@code dcbo-backend}
 * requirement fixed for this endpoint.
 */
public class InvalidReferenceException extends RuntimeException {

	/**
	 * Creates the exception with a message naming the offending field and id.
	 *
	 * @param message surfaced in the 400 body's {@code message}, e.g. {@code "carroId: carro nao
	 *     encontrado: <id>"}
	 */
	public InvalidReferenceException(String message) {
		super(message);
	}
}
