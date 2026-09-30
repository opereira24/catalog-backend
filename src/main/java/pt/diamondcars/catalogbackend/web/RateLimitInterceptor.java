package pt.diamondcars.catalogbackend.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import pt.diamondcars.catalogbackend.web.exception.RateLimitExceededException;

/**
 * In-memory, per-IP rate limiter for {@code POST /api/leads} (TASK-015 requirement 4): the
 * ({@code app.leads.rate-limit.max-requests + 1})-th submission from the same IP within {@code
 * app.leads.rate-limit.window-minutes} is rejected with {@link RateLimitExceededException},
 * mapped to 429 by {@code ApiExceptionHandler}. Registered only for {@code /api/leads} by {@link
 * pt.diamondcars.catalogbackend.config.CorsConfig#addInterceptors}.
 *
 * <p>ASSUNÇÃO ({@code backlog/tasks/TASK-015.md}, {@code ## Notas}): the limit is tracked per JVM
 * instance, not distributed (e.g. via Redis) — a distributed limiter would need infrastructure the
 * team does not manage (DIRECTIVES.md). With more than one Render instance running this service,
 * the effective limit ends up per instance, not truly per visitor.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

	private final int maxRequests;
	private final Duration window;
	private final ConcurrentHashMap<String, Deque<Instant>> submissionsByIp = new ConcurrentHashMap<>();

	/**
	 * Creates the interceptor with its configured limit.
	 *
	 * @param maxRequests maximum submissions allowed per IP within {@code window}
	 * @param windowMinutes width, in minutes, of the sliding window submissions are counted over
	 */
	public RateLimitInterceptor(
			@Value("${app.leads.rate-limit.max-requests:5}") int maxRequests,
			@Value("${app.leads.rate-limit.window-minutes:10}") long windowMinutes) {
		this.maxRequests = maxRequests;
		this.window = Duration.ofMinutes(windowMinutes);
	}

	/**
	 * Counts this request against its caller IP's sliding window, rejecting it once the configured
	 * limit is exceeded.
	 *
	 * @param request the incoming request, used only for {@link HttpServletRequest#getRemoteAddr()}
	 * @param response unused; a rejection is signalled by throwing, not by writing to this directly
	 * @param handler unused
	 * @return always {@code true} when it returns at all — a rejection throws instead
	 * @throws RateLimitExceededException when the caller IP has already submitted {@code
	 *     maxRequests} times within the current window
	 */
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		String ip = request.getRemoteAddr();
		Deque<Instant> timestamps = submissionsByIp.computeIfAbsent(ip, key -> new ConcurrentLinkedDeque<>());
		synchronized (timestamps) {
			Instant now = Instant.now();
			pruneExpired(timestamps, now);
			if (timestamps.size() >= maxRequests) {
				throw new RateLimitExceededException(ip);
			}
			timestamps.addLast(now);
		}
		return true;
	}

	/**
	 * Discards every timestamp older than {@link #window}, so the per-IP deque never grows
	 * unbounded and expired submissions never count against the current window.
	 *
	 * @param timestamps the caller IP's submission timestamps, oldest first
	 * @param now the instant to measure the window from
	 */
	private void pruneExpired(Deque<Instant> timestamps, Instant now) {
		Instant cutoff = now.minus(window);
		while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
			timestamps.pollFirst();
		}
	}
}
