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
 * <p><b>TASK-015 review r1, BLOQUEADOR 2</b>: the "per IP" key is resolved by {@link
 * #resolveClientIp(HttpServletRequest)}, which prefers the first address in {@code
 * X-Forwarded-For} over {@link HttpServletRequest#getRemoteAddr()}. Behind Render's proxy, every
 * visitor's {@code getRemoteAddr()} is the proxy itself, not the visitor — without this, every
 * visitor on the site shared one single bucket, making the limit a global ~5-submissions/10-min
 * cap on the whole site rather than a per-visitor one. ASSUNÇÃO: trusting the left-most {@code
 * X-Forwarded-For} entry unconditionally (instead of Tomcat's {@code RemoteIpValve}/{@code
 * server.forward-headers-strategy}, which validates the header only came from a configured
 * trusted-proxy CIDR) is safe specifically because this service is only ever reachable through
 * Render's own proxy (never exposed with a public IP of its own) — this must be re-checked at
 * deploy time if that topology ever changes, since a directly-reachable instance would let a
 * client trivially spoof this header to bypass the whole limiter.
 *
 * <p><b>IMPORTANTE 3</b>: {@link #preHandle} only counts {@code POST} requests — a CORS preflight
 * {@code OPTIONS} request (or any other method) passes straight through without being counted.
 * Before this, every {@code OPTIONS} preflight the browser sent ahead of the actual {@code POST}
 * consumed one unit of the same visitor's quota, leaving a real visitor with far fewer than the
 * configured number of usable submissions.
 *
 * <p>ASSUNÇÃO ({@code backlog/tasks/TASK-015.md}, {@code ## Notas}): the limit is tracked per JVM
 * instance, not distributed (e.g. via Redis) — a distributed limiter would need infrastructure the
 * team does not manage (DIRECTIVES.md). With more than one Render instance running this service,
 * the effective limit ends up per instance, not truly per visitor.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

	/** Standard de-facto header a trusted proxy (Render) sets to the original client IP. */
	private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

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
	 * limit is exceeded. Only {@code POST} requests are counted (review r1, IMPORTANTE 3): a CORS
	 * preflight {@code OPTIONS} (or any other method reaching this path) passes straight through.
	 *
	 * @param request the incoming request; its method decides whether it is counted at all, and
	 *     {@link #resolveClientIp(HttpServletRequest)} decides the bucket it counts against
	 * @param response unused; a rejection is signalled by throwing, not by writing to this directly
	 * @param handler unused
	 * @return always {@code true} when it returns at all — a rejection throws instead
	 * @throws RateLimitExceededException when the caller IP has already submitted {@code
	 *     maxRequests} times within the current window
	 */
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!"POST".equalsIgnoreCase(request.getMethod())) {
			return true;
		}
		String ip = resolveClientIp(request);
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
	 * Resolves the address this request is rate-limited against (review r1, BLOQUEADOR 2):
	 * whichever address the visitor's own browser actually has, not the address of whatever proxy
	 * relayed the request to this instance.
	 *
	 * @param request the incoming request
	 * @return the first, left-most address in {@value #FORWARDED_FOR_HEADER} when that header is
	 *     present and non-blank; otherwise {@link HttpServletRequest#getRemoteAddr()}
	 */
	private static String resolveClientIp(HttpServletRequest request) {
		String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			return forwardedFor.split(",")[0].trim();
		}
		return request.getRemoteAddr();
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
