package pt.diamondcars.catalogbackend.web.exception;

/**
 * Thrown when {@link pt.diamondcars.catalogbackend.web.dto.TransactionRequest#data()} is neither a
 * plain {@code "YYYY-MM-DD"} calendar date nor a full ISO-8601 instant (the two formats {@code dcbo}
 * sends), or resolves to a date outside {@code [0001-01-01, 9999-12-31]} (TASK-005 AC B.1). Mapped
 * to 400 by {@link pt.diamondcars.catalogbackend.web.ApiExceptionHandler}. Ported from {@code
 * dcbo-backend}.
 *
 * <p>The message repeats the raw value on purpose (the caller sees what was refused); {@code
 * TransactionRequest#data} is capped at 64 characters by {@code @Size} so the echo stays small.
 */
public class InvalidTransactionDateException extends RuntimeException {

	/**
	 * Creates the exception with a message naming the offending field and raw value.
	 *
	 * @param rawValue the value of {@code data} that was refused
	 */
	public InvalidTransactionDateException(String rawValue) {
		super("data: valor invalido, esperado YYYY-MM-DD ou instante ISO-8601 (ex. 2026-03-31T23:30:00Z): " + rawValue);
	}
}
