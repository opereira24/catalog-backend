package pt.diamondcars.catalogbackend.web.exception;

/**
 * Thrown when featuring a car ({@code destaque = true}) would exceed the maximum number of
 * simultaneously featured cars (TASK-003, AC E.1), mapped to 409 by {@link
 * pt.diamondcars.catalogbackend.web.ApiExceptionHandler}. The message is the sentence {@code
 * dcbo/src/pages/highlights.js} already shows to the user. Ported from {@code dcbo-backend}.
 */
public class HighlightLimitExceededException extends RuntimeException {

	/**
	 * Creates the exception with a message stating the limit that was reached.
	 *
	 * @param limit the maximum number of cars allowed to be featured at the same time
	 */
	public HighlightLimitExceededException(int limit) {
		super("Limite de " + limit + " carros em destaque atingido");
	}
}
