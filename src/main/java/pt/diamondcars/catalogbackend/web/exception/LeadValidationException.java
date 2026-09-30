package pt.diamondcars.catalogbackend.web.exception;

/**
 * Thrown by {@code LeadService} when a lead field, once sanitized, no longer fits the length
 * bound that {@code POST /internal/leads} of {@code dcbo-backend} also enforces — mapped to 400
 * by {@code pt.diamondcars.catalogbackend.web.ApiExceptionHandler} (TASK-015 review r1, BLOQUEADOR
 * 1). Checking the bound after sanitizing, instead of on the raw {@code LeadRequest} field, is
 * deliberate: HTML-entity escaping can grow a value past a limit it satisfied before escaping
 * (e.g. every {@code /} becomes the six characters {@code &#x2F;}), and it is the sanitized value
 * that is actually persisted and forwarded.
 */
public class LeadValidationException extends RuntimeException {

	/**
	 * Creates the exception for one field that failed its post-sanitization length bound.
	 *
	 * @param field the name of the field that failed, exactly as it appears in {@code LeadRequest}
	 * @param message a human-readable description of the violated bound
	 */
	public LeadValidationException(String field, String message) {
		super(field + ": " + message);
	}
}
