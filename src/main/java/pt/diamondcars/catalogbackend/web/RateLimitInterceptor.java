package pt.diamondcars.catalogbackend.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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
 * <p><b>TASK-015 review r1, BLOQUEADOR 2 / review r2, IMPORTANTE 1</b>: the "per IP" key is
 * resolved by {@link #resolveClientIp(HttpServletRequest)} from {@code X-Forwarded-For} instead of
 * {@link HttpServletRequest#getRemoteAddr()} alone, because behind Render's proxy every visitor's
 * {@code getRemoteAddr()} is the proxy itself, not the visitor — without this, every visitor on
 * the site shared one single bucket. The r1 fix trusted the <b>left-most</b> entry of {@code
 * X-Forwarded-For} unconditionally, which review r2 proved trivially bypassable: a proxy
 * <b>appends</b> the address it saw to the header, it never overwrites what the client already
 * sent, so the left-most entry is always attacker-controlled and a caller that sends a fresh,
 * fabricated left-most entry on every request gets a fresh bucket every time (12 accepted
 * submissions, 0 rejections, in the review's real-HTTP reproduction). The fix reads from the
 * <b>right</b> instead — {@link #resolveClientIp(HttpServletRequest)} — counting {@link
 * #trustedProxyHops} entries in from the right, which only the real, physical proxy chain in front
 * of this instance can append; a client can prepend as many fake entries as it wants without
 * moving that position. ASSUNÇÃO: {@code app.leads.rate-limit.trusted-proxy-hops} defaults to
 * {@code 1} (Render's own proxy is the only hop between the visitor and this instance); this must
 * be re-checked at deploy time against a real request through Render (e.g. logging the resolved
 * {@code X-Forwarded-For} value once) — a topology with an extra hop in front of Render (a CDN,
 * for instance) would need this raised, and a directly-reachable instance with no trusted proxy at
 * all would need it set to {@code 0} (trust {@link HttpServletRequest#getRemoteAddr()} only).
 *
 * <p><b>IMPORTANTE 3</b>: {@link #preHandle} only counts {@code POST} requests — a CORS preflight
 * {@code OPTIONS} request (or any other method) passes straight through without being counted.
 * Before this, every {@code OPTIONS} preflight the browser sent ahead of the actual {@code POST}
 * consumed one unit of the same visitor's quota, leaving a real visitor with far fewer than the
 * configured number of usable submissions.
 *
 * <p><b>Review r2, IMPORTANTE 1 (agravante)</b>: {@link #submissionsByIp} only prunes a given IP's
 * own expired timestamps when that same IP makes another request (see {@link #preHandle}) — an IP
 * that submits once (spoofed or real) and never comes back leaves a permanent entry in the map.
 * Combined with the left-most bypass above, an attacker could grow the map without bound at near
 * zero cost. {@link #evictExpiredEntries()} sweeps the whole map on a fixed schedule ({@code
 * app.leads.rate-limit.cleanup-interval-ms}, default 5 minutes) and drops any IP whose deque has
 * gone empty, so the map's size is bounded by the number of distinct visitors active within the
 * last window, not by the number of requests ever received.
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
	private final int trustedProxyHops;
	private final ConcurrentHashMap<String, Deque<Instant>> submissionsByIp = new ConcurrentHashMap<>();

	/**
	 * Creates the interceptor with its configured limit.
	 *
	 * @param maxRequests maximum submissions allowed per IP within {@code window}
	 * @param windowMinutes width, in minutes, of the sliding window submissions are counted over
	 * @param trustedProxyHops number of trusted reverse-proxy hops in front of this instance; the
	 *     caller's IP is read {@code trustedProxyHops} entries in from the right of {@code
	 *     X-Forwarded-For} (review r2, IMPORTANTE 1), never from the left
	 */
	public RateLimitInterceptor(
			@Value("${app.leads.rate-limit.max-requests:5}") int maxRequests,
			@Value("${app.leads.rate-limit.window-minutes:10}") long windowMinutes,
			@Value("${app.leads.rate-limit.trusted-proxy-hops:1}") int trustedProxyHops) {
		this.maxRequests = maxRequests;
		this.window = Duration.ofMinutes(windowMinutes);
		this.trustedProxyHops = trustedProxyHops;
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
	 * Resolves the address this request is rate-limited against (review r1, BLOQUEADOR 2; review
	 * r2, IMPORTANTE 1): the entry {@link #trustedProxyHops} positions in from the <b>right</b> of
	 * {@value #FORWARDED_FOR_HEADER}, never the left. A reverse proxy <b>appends</b> the address it
	 * saw to this header instead of replacing it, so every entry a proxy adds ends up to the right
	 * of whatever the client already sent — the left-most entry is always attacker-controlled, but
	 * the entry {@code trustedProxyHops} hops from the right can only have been written by this
	 * instance's own trusted proxy chain, which a client cannot move or fabricate.
	 *
	 * @param request the incoming request
	 * @return the trusted entry in {@value #FORWARDED_FOR_HEADER}, counted from the right, when
	 *     that header is present, non-blank, and has at least {@link #trustedProxyHops} entries;
	 *     otherwise {@link HttpServletRequest#getRemoteAddr()}
	 */
	private String resolveClientIp(HttpServletRequest request) {
		String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
		if (forwardedFor != null && !forwardedFor.isBlank()) {
			String[] entries = forwardedFor.split(",");
			int clientIndex = entries.length - trustedProxyHops;
			if (clientIndex >= 0 && clientIndex < entries.length) {
				return entries[clientIndex].trim();
			}
		}
		return request.getRemoteAddr();
	}

	/**
	 * Discards every timestamp older than {@link #window}, so the per-IP deque never grows
	 * unbounded and expired submissions never count against the current window.
	 *
	 * <p>Only prunes the deque passed in — it does not remove the IP's entry from {@link
	 * #submissionsByIp} even when the deque ends up empty, because the caller ({@link #preHandle})
	 * immediately adds a fresh timestamp to it afterwards. {@link #evictExpiredEntries()} is what
	 * removes now-empty entries for IPs that never submit again.
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

	/**
	 * Sweeps every tracked IP and drops it from {@link #submissionsByIp} once its deque has no
	 * timestamps left within {@link #window} (review r2, IMPORTANTE 1 — agravante). Without this,
	 * an IP that submits once and never returns — spoofed or real — keeps a permanent entry in the
	 * map, which an attacker could exploit to grow it without bound almost for free, since {@link
	 * #pruneExpired(Deque, Instant)} alone only ever runs for an IP that keeps submitting.
	 *
	 * <p>Runs on a fixed delay ({@code app.leads.rate-limit.cleanup-interval-ms}, default 5
	 * minutes) rather than per-request, because it has to visit every tracked IP, not just the
	 * current caller's.
	 */
	@Scheduled(fixedDelayString = "${app.leads.rate-limit.cleanup-interval-ms:300000}")
	void evictExpiredEntries() {
		Instant now = Instant.now();
		for (String ip : submissionsByIp.keySet()) {
			submissionsByIp.computeIfPresent(
					ip,
					(key, timestamps) -> {
						synchronized (timestamps) {
							pruneExpired(timestamps, now);
							return timestamps.isEmpty() ? null : timestamps;
						}
					});
		}
	}

	/**
	 * Test-only introspection of how many distinct IPs are currently tracked, regardless of whether
	 * their deque still holds any timestamp. Used to assert {@link #evictExpiredEntries()} actually
	 * shrinks the map instead of only emptying deques in place.
	 *
	 * @return the number of IP keys currently present in {@link #submissionsByIp}
	 */
	int trackedIpCount() {
		return submissionsByIp.size();
	}
}
