package pt.diamondcars.catalogbackend.web.dto;

import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;

/**
 * Consistent error response body for every 4xx/5xx raised by the API (TASK-014 requirement 7),
 * shared by every controller advice this task's siblings add (TASK-015, TASK-016), so clients
 * only ever parse one error format — mirrors {@code dcbo-backend}'s equivalent class.
 *
 * @param timestamp when the error was produced
 * @param status the HTTP status code, e.g. {@code 404}
 * @param error the HTTP status reason phrase, e.g. {@code "Not Found"}
 * @param message a human-readable description of what went wrong
 * @param path the request URI that caused the error
 */
public record ApiError(OffsetDateTime timestamp, int status, String error, String message, String path) {

	/**
	 * Builds an {@link ApiError} for the given status, filling {@link #timestamp} with the current
	 * instant and {@link #error} with the status's standard reason phrase.
	 *
	 * @param status the HTTP status to report
	 * @param message a human-readable description of what went wrong
	 * @param path the request URI that caused the error
	 * @return the resulting error body
	 */
	public static ApiError of(HttpStatus status, String message, String path) {
		return new ApiError(OffsetDateTime.now(), status.value(), status.getReasonPhrase(), message, path);
	}
}
