package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import pt.diamondcars.catalogbackend.web.exception.RateLimitExceededException;

/**
 * Focused unit tests of {@link RateLimitInterceptor} in isolation (no Spring context, no {@code
 * MockMvc}), covering TASK-015 review r2, IMPORTANTE 1: reading the trusted client IP from the
 * right of {@code X-Forwarded-For} instead of the left, and evicting stale entries from the
 * in-memory map so it does not grow without bound.
 */
class RateLimitInterceptorTest {

	private static final MockHttpServletResponse UNUSED_RESPONSE = new MockHttpServletResponse();

	/**
	 * Builds a {@code POST /api/leads} request carrying the given {@code X-Forwarded-For} value (or
	 * none, when {@code forwardedFor} is {@code null}), always from the same simulated proxy
	 * address, so every assertion below is exercising {@link
	 * RateLimitInterceptor#resolveClientIp} rather than {@code getRemoteAddr()}.
	 *
	 * @param forwardedFor the raw {@code X-Forwarded-For} header value, or {@code null} to omit it
	 * @return a POST request ready to pass to {@code preHandle}
	 */
	private static MockHttpServletRequest postRequest(String forwardedFor) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/leads");
		request.setRemoteAddr("10.0.0.1");
		if (forwardedFor != null) {
			request.addHeader("X-Forwarded-For", forwardedFor);
		}
		return request;
	}

	/**
	 * Review r2, IMPORTANTE 1: with the default of one trusted proxy hop, a client that sends a
	 * different, fabricated left-most {@code X-Forwarded-For} entry on every request (simulating a
	 * bot rotating a fake header value) is still rate-limited as a single visitor, because the
	 * right-most entry — the one a real trusted proxy would have appended — never changes.
	 */
	@Test
	void resolvesClientIpFromTheRightOfForwardedForSoASpoofedLeftEntryCannotBypassTheLimit() {
		RateLimitInterceptor interceptor = new RateLimitInterceptor(5, 10, 1);

		for (int i = 0; i < 5; i++) {
			boolean allowed =
					interceptor.preHandle(
							postRequest("198.51.100." + i + ", 203.0.113.99"), UNUSED_RESPONSE, null);
			assertThat(allowed).isTrue();
		}

		assertThatThrownBy(
						() ->
								interceptor.preHandle(
										postRequest("198.51.100.999, 203.0.113.99"), UNUSED_RESPONSE, null))
				.isInstanceOf(RateLimitExceededException.class);
	}

	/**
	 * With zero trusted proxy hops, only {@code getRemoteAddr()} is trusted — {@code
	 * X-Forwarded-For} is ignored entirely, so every request from the same simulated proxy address
	 * shares one bucket regardless of the header.
	 */
	@Test
	void zeroTrustedProxyHopsIgnoresForwardedForEntirely() {
		RateLimitInterceptor interceptor = new RateLimitInterceptor(5, 10, 0);

		for (int i = 0; i < 5; i++) {
			boolean allowed =
					interceptor.preHandle(postRequest("203.0.113." + i), UNUSED_RESPONSE, null);
			assertThat(allowed).isTrue();
		}

		assertThatThrownBy(
						() -> interceptor.preHandle(postRequest("203.0.113.250"), UNUSED_RESPONSE, null))
				.isInstanceOf(RateLimitExceededException.class);
	}

	/**
	 * When {@code X-Forwarded-For} has fewer entries than the configured number of trusted hops
	 * (e.g. a malformed or missing header), {@link RateLimitInterceptor#resolveClientIp} falls back
	 * to {@code getRemoteAddr()} instead of throwing or resolving to a blank key.
	 */
	@Test
	void fallsBackToRemoteAddrWhenForwardedForHasFewerEntriesThanTrustedHops() {
		RateLimitInterceptor interceptor = new RateLimitInterceptor(1, 10, 2);

		assertThat(interceptor.preHandle(postRequest("203.0.113.10"), UNUSED_RESPONSE, null)).isTrue();
		assertThatThrownBy(
						() -> interceptor.preHandle(postRequest("203.0.113.20"), UNUSED_RESPONSE, null))
				.isInstanceOf(RateLimitExceededException.class);
	}

	/**
	 * Review r2, IMPORTANTE 1 (agravante): an IP that submits once and never returns leaves a
	 * permanent, empty-after-pruning entry in the map unless something sweeps it. {@link
	 * RateLimitInterceptor#evictExpiredEntries()} removes it once its deque has no timestamps left
	 * within the (here, zero-length) window, keeping the map's size bounded by active visitors
	 * instead of by the total number of distinct IPs ever seen.
	 *
	 * @throws InterruptedException never, in practice — required by {@link Thread#sleep(long)}
	 */
	@Test
	void evictExpiredEntriesRemovesIpsThatNeverSubmitAgain() throws InterruptedException {
		RateLimitInterceptor interceptor = new RateLimitInterceptor(5, 0, 1);

		for (int i = 0; i < 50; i++) {
			interceptor.preHandle(postRequest("203.0.113." + i), UNUSED_RESPONSE, null);
		}
		assertThat(interceptor.trackedIpCount()).isEqualTo(50);

		// window-minutes=0 means every timestamp already recorded is expired as soon as any
		// measurable time passes, without needing to wait for a real window to elapse.
		Thread.sleep(5);
		interceptor.evictExpiredEntries();

		assertThat(interceptor.trackedIpCount()).isZero();
	}
}
