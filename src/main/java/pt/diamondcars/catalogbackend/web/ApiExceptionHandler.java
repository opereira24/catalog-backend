package pt.diamondcars.catalogbackend.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import pt.diamondcars.catalogbackend.web.dto.ApiError;
import pt.diamondcars.catalogbackend.web.exception.CarNotFoundException;
import pt.diamondcars.catalogbackend.web.exception.InactiveUserException;
import pt.diamondcars.catalogbackend.web.exception.LeadValidationException;
import pt.diamondcars.catalogbackend.web.exception.RateLimitExceededException;

/**
 * Central exception-to-HTTP-response translation for the whole API, per TASK-014 requirement 7.
 * Produces the same {@link ApiError} shape ({@code timestamp}, {@code status}, {@code error},
 * {@code message}, {@code path}) for every mapped exception, so clients only ever parse one error
 * format. Shared by every endpoint added by the tasks that follow (TASK-015, TASK-016): a new
 * exception type in those tasks is mapped here by adding one more {@code @ExceptionHandler}
 * method, never by duplicating this class — mirrors {@code dcbo-backend}'s equivalent class.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	/**
	 * Maps {@link CarNotFoundException} ({@code GET /api/cars/{id}} for an id that does not exist,
	 * requirement 2) to 404.
	 *
	 * @param exception the exception thrown by the service layer
	 * @param request the failed request, used to report {@link ApiError#path()}
	 * @return the 404 response body
	 */
	@ExceptionHandler(CarNotFoundException.class)
	public ResponseEntity<ApiError> handleCarNotFound(CarNotFoundException exception, HttpServletRequest request) {
		return respond(HttpStatus.NOT_FOUND, exception.getMessage(), request);
	}

	/**
	 * Maps a {@code jakarta.validation} failure on an {@code @Valid @RequestBody} argument to 400,
	 * with a message that names every invalid field. Not exercised by this task's read-only
	 * endpoints, added here so TASK-015's {@code POST /api/leads} can reuse this handler without
	 * duplicating it.
	 *
	 * @param exception the validation failure Spring MVC raises for an invalid request body
	 * @param request the failed request, used to report {@link ApiError#path()}
	 * @return the 400 response body
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleInvalidBody(
			MethodArgumentNotValidException exception, HttpServletRequest request) {
		String message =
				exception.getBindingResult().getFieldErrors().stream()
						.map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
						.collect(Collectors.joining("; "));
		return respond(HttpStatus.BAD_REQUEST, message, request);
	}

	/**
	 * Maps a malformed or unreadable request body (invalid JSON, wrong content type body, ...) to
	 * 400, instead of Spring MVC's default body-less 400.
	 *
	 * @param exception the exception Spring MVC raises when the {@code @RequestBody} cannot be
	 *     deserialised
	 * @param request the failed request, used to report {@link ApiError#path()}
	 * @return the 400 response body
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> handleMalformedBody(
			HttpMessageNotReadableException exception, HttpServletRequest request) {
		return respond(HttpStatus.BAD_REQUEST, "Corpo do pedido invalido ou malformado", request);
	}

	/**
	 * Maps a path variable or query parameter that cannot be converted to its expected type (e.g. a
	 * non-UUID {@code {id}} in the path, or a non-numeric {@code precoMin}) to 400, instead of
	 * Spring MVC's default body-less 400.
	 *
	 * @param exception the exception Spring MVC raises when argument conversion fails
	 * @param request the failed request, used to report {@link ApiError#path()}
	 * @return the 400 response body
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiError> handleTypeMismatch(
			MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
		return respond(
				HttpStatus.BAD_REQUEST, "Parametro '" + exception.getName() + "' com valor invalido", request);
	}

	/**
	 * Maps {@link RateLimitExceededException} (too many {@code POST /api/leads} submissions from
	 * the same IP within the configured window, TASK-015 requirement 4) to 429.
	 *
	 * @param exception the exception {@code RateLimitInterceptor} throws
	 * @param request the failed request, used to report {@link ApiError#path()}
	 * @return the 429 response body
	 */
	@ExceptionHandler(RateLimitExceededException.class)
	public ResponseEntity<ApiError> handleRateLimitExceeded(
			RateLimitExceededException exception, HttpServletRequest request) {
		return respond(HttpStatus.TOO_MANY_REQUESTS, "Demasiados pedidos - tente novamente mais tarde", request);
	}

	/**
	 * Maps {@link LeadValidationException} (a lead field that no longer fits its length bound once
	 * sanitized, TASK-015 review r1, BLOQUEADOR 1) to 400.
	 *
	 * @param exception the exception {@code LeadService} throws
	 * @param request the failed request, used to report {@link ApiError#path()}
	 * @return the 400 response body
	 */
	@ExceptionHandler(LeadValidationException.class)
	public ResponseEntity<ApiError> handleLeadValidation(LeadValidationException exception, HttpServletRequest request) {
		return respond(HttpStatus.BAD_REQUEST, exception.getMessage(), request);
	}

	/**
	 * Maps {@link InactiveUserException} (the authenticated caller's {@code app_users} profile has
	 * {@code active = false}, TASK-002 requirement 5) to 403. Thrown by {@code
	 * ActiveUserInterceptor} before the controller method, so before any {@code @PreAuthorize}.
	 *
	 * @param exception the exception thrown by the interceptor
	 * @param request the failed request, used to report {@link ApiError#path()}
	 * @return the 403 response body
	 */
	@ExceptionHandler(InactiveUserException.class)
	public ResponseEntity<ApiError> handleInactiveUser(InactiveUserException exception, HttpServletRequest request) {
		return respond(HttpStatus.FORBIDDEN, exception.getMessage(), request);
	}

	/**
	 * Maps Spring Security's {@link AccessDeniedException} (raised by {@code @PreAuthorize} when an
	 * authenticated caller lacks the required role) to 403 in the {@link ApiError} envelope.
	 * Required, not cosmetic: without it {@link #handleUnexpected(Exception, HttpServletRequest)}
	 * would turn every role refusal into a 500 (TASK-002, AC D.6).
	 *
	 * @param exception the exception {@code @PreAuthorize} raises when denying access
	 * @param request the failed request, used to report {@link ApiError#path()}
	 * @return the 403 response body
	 */
	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException exception, HttpServletRequest request) {
		return respond(HttpStatus.FORBIDDEN, "Autenticado, mas sem a role exigida para esta operacao", request);
	}

	/**
	 * Maps a database constraint violation to 409, without propagating the underlying Postgres
	 * message — which names internal constraint/column identifiers that should not leak to a
	 * client. The full exception is still logged server-side for diagnosis. Not exercised by this
	 * task's read-only endpoints, added here so TASK-016's upsert/reconcile endpoints can reuse this
	 * handler without duplicating it.
	 *
	 * @param exception the exception the persistence layer raises on a constraint violation
	 * @param request the failed request, used to report {@link ApiError#path()}
	 * @return the 409 response body
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ApiError> handleDataIntegrityViolation(
			DataIntegrityViolationException exception, HttpServletRequest request) {
		log.warn("Data integrity violation handling {}", request.getRequestURI(), exception);
		return respond(HttpStatus.CONFLICT, "Pedido em conflito com o estado atual dos dados", request);
	}

	/**
	 * Maps the standard Spring MVC exceptions that already carry their own correct client-error
	 * status (unknown route, method not allowed, unsupported/not-acceptable content type, missing
	 * request parameter) to that same status, instead of letting {@link #handleUnexpected(Exception,
	 * HttpServletRequest)} below swallow them as 500 — every one of these types implements {@link
	 * ErrorResponse}, so its own {@link ErrorResponse#getStatusCode()} is always the correct status
	 * to report.
	 *
	 * @param exception one of the listed {@link ErrorResponse} exceptions Spring MVC raises for a
	 *     client-side routing/content-negotiation error (declared as {@code Exception} because
	 *     {@code @ExceptionHandler} requires a {@link Throwable} parameter type, and {@link
	 *     ErrorResponse} is an interface, not a {@link Throwable})
	 * @param request the failed request, used to report {@link ApiError#path()}
	 * @return the response with the exception's own status, in the same {@link ApiError} envelope
	 */
	@ExceptionHandler({
		NoResourceFoundException.class,
		HttpRequestMethodNotSupportedException.class,
		HttpMediaTypeNotSupportedException.class,
		HttpMediaTypeNotAcceptableException.class,
		MissingServletRequestParameterException.class,
		ErrorResponseException.class
	})
	public ResponseEntity<ApiError> handleSpringMvcClientError(Exception exception, HttpServletRequest request) {
		HttpStatusCode statusCode = ((ErrorResponse) exception).getStatusCode();
		HttpStatus status = HttpStatus.valueOf(statusCode.value());
		return respond(status, status.getReasonPhrase(), request);
	}

	/**
	 * Fallback for every exception not mapped above, so the API never leaks a stack trace or an
	 * inconsistent envelope for an unanticipated failure. The full exception is still logged
	 * server-side for diagnosis; only a generic message reaches the client.
	 *
	 * <p>Never reaches the {@link ErrorResponse} exceptions mapped by {@link
	 * #handleSpringMvcClientError(Exception, HttpServletRequest)} above: {@code @ExceptionHandler}
	 * resolution always picks the most specific declared exception type for the thrown exception,
	 * regardless of method declaration order.
	 *
	 * @param exception the unmapped exception
	 * @param request the failed request, used to report {@link ApiError#path()}
	 * @return the 500 response body
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
		log.error("Unexpected error handling {}", request.getRequestURI(), exception);
		return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno inesperado", request);
	}

	private ResponseEntity<ApiError> respond(HttpStatus status, String message, HttpServletRequest request) {
		return ResponseEntity.status(status).body(ApiError.of(status, message, request.getRequestURI()));
	}
}
