package pt.diamondcars.catalogbackend.web.exception;

/**
 * Thrown by {@link pt.diamondcars.catalogbackend.web.RateLimitInterceptor} when the calling IP has
 * exceeded the configured submission rate for {@code POST /api/leads} (TASK-015 requirement 4),
 * mapped to 429 by {@code pt.diamondcars.catalogbackend.web.ApiExceptionHandler}.
 */
public class RateLimitExceededException extends RuntimeException {

	/**
	 * Creates the exception naming the rate-limited IP, for server-side logging only — the client
	 * never sees anything beyond {@code ApiExceptionHandler}'s generic 429 body.
	 *
	 * @param ip the rate-limited client IP
	 */
	public RateLimitExceededException(String ip) {
		super("Limite de submissoes excedido para o IP " + ip);
	}
}
