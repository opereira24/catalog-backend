package pt.diamondcars.catalogbackend.config;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.ResourceRetriever;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Auth0 signing keys (JWKS) behind the production {@code JwtDecoder}, built so that a slow,
 * hung or unreachable Auth0 can never hold more than a handful of request threads, whatever an
 * anonymous client sends (TASK-002, review r1, IMPORTANTE 1).
 *
 * <p>Why not the sources that ship with Spring Security or Nimbus (each one measured): Spring's
 * {@code withIssuerLocation} resolves the OIDC discovery under a lock that does not remember a
 * failure, so concurrent requests queue for 3 s each; its JWKS source locks on every lookup and
 * fetches the JWKS again for every unknown {@code kid}. Nimbus' {@code CachingJWKSetSource} lets an
 * unbounded number of requests wait for an in-flight fetch, and reports a rate-limited unknown
 * {@code kid} as an unavailable key source (500) instead of an invalid token (401). The fetch is
 * {@link DeadlineResourceRetriever} (a limit on the whole fetch, not only on each read, and on its
 * size) and the parsing Nimbus' {@link JWKSet#parse(String)}.
 *
 * <p>Rules, in the order a lookup applies them:
 *
 * <ol>
 *   <li>A {@code kid} found in the cached keys is answered from memory: no lock, no network, even
 *       while Auth0 is down (the last good keys are kept until a fetch succeeds). Keys older than
 *       {@code timeToLive} are refreshed by a background virtual thread, never by the request.
 *   <li>Otherwise (no keys yet, or unknown {@code kid}) one request fetches the JWKS, and only if
 *       the previous attempt, successful or not, ended at least {@code minFetchInterval} ago. That
 *       interval is the negative cache after a failure and the rate limit for invented {@code kid}s.
 *   <li>While a fetch is in flight, at most {@code maxWaitingRequests} other requests wait for it
 *       (at most {@code maxWait}); the rest are answered at once from what is cached.
 *   <li>The answer: a matching key; or none (the decoder answers 401) when the keys are known to be
 *       current; or {@link KeySourceException} (the request ends in 500) when there are no keys or
 *       the last fetch failed, because then the token may be valid and the back-office must not end
 *       the user's session over a server-side failure.
 * </ol>
 *
 * <p>Every failed fetch is logged once, as one {@code WARN} line without a stack trace; the
 * requests it fails are not logged individually.
 */
final class Auth0JwkSource implements JWKSource<SecurityContext> {

	private static final Logger log = LoggerFactory.getLogger(Auth0JwkSource.class);

	// Production limits. Private on purpose: the tests state them as literals (review r2, S-e), so
	// changing one of them breaks a test instead of moving the test along with it.

	/** Minimum time between the end of one JWKS fetch attempt and the start of the next. */
	private static final Duration MIN_FETCH_INTERVAL = Duration.ofSeconds(10);

	/** Age after which cached keys are refreshed in the background (Nimbus' default cache life). */
	private static final Duration KEYS_TIME_TO_LIVE = Duration.ofMinutes(5);

	/** How many requests may wait for an in-flight fetch; the others never block. */
	private static final int MAX_WAITING_REQUESTS = 8;

	/** Upper bound of a JWKS response, Nimbus' default; Auth0's is about 2 KB. */
	private static final int JWKS_SIZE_LIMIT_BYTES = 50 * 1024;

	private final URL jwkSetUrl;
	private final ResourceRetriever retriever;
	private final long minFetchIntervalNanos;
	private final long timeToLiveNanos;
	private final Duration maxWait;
	private final LongSupplier nanoClock;
	private final ReentrantLock fetchLock = new ReentrantLock();
	private final Semaphore waitingRequests;
	private final AtomicBoolean backgroundRefreshRunning = new AtomicBoolean();
	private volatile State state = State.EMPTY;

	/**
	 * Creates a source with explicit limits (tests use short intervals and a fake clock).
	 *
	 * @param jwkSetUrl where the JWKS is fetched from
	 * @param retriever performs the HTTP GET, with its own timeouts
	 * @param minFetchInterval see {@link #MIN_FETCH_INTERVAL}
	 * @param timeToLive see {@link #KEYS_TIME_TO_LIVE}
	 * @param maxWaitingRequests see {@link #MAX_WAITING_REQUESTS}
	 * @param maxWait the longest a request waits for an in-flight fetch
	 * @param nanoClock monotonic clock in nanoseconds ({@link System#nanoTime()} in production)
	 */
	Auth0JwkSource(
			URL jwkSetUrl,
			ResourceRetriever retriever,
			Duration minFetchInterval,
			Duration timeToLive,
			int maxWaitingRequests,
			Duration maxWait,
			LongSupplier nanoClock) {
		this.jwkSetUrl = jwkSetUrl;
		this.retriever = retriever;
		this.minFetchIntervalNanos = minFetchInterval.toNanos();
		this.timeToLiveNanos = timeToLive.toNanos();
		this.waitingRequests = new Semaphore(maxWaitingRequests);
		this.maxWait = maxWait;
		this.nanoClock = nanoClock;
	}

	/**
	 * The production source for an Auth0 tenant: the JWKS at {@code <issuer>.well-known/jwks.json}
	 * (where Auth0 always publishes it, so no OIDC discovery round trip). The connection and the
	 * response headers must arrive within {@code timeout}, the whole response within twice that, and
	 * a request waits for an in-flight fetch at most as long, so neither the request that fetches
	 * nor the ones that wait for it are held longer than {@code 2 x timeout}.
	 *
	 * @param issuerUri the tenant's issuer as {@link Auth0Issuer} accepts it, e.g. {@code
	 *     https://oteustand.eu.auth0.com/}
	 * @param timeout limit for the connection and the response headers; half the limit of a fetch
	 * @return the source; nothing is fetched until the first token needs a key
	 */
	static Auth0JwkSource forIssuer(String issuerUri, Duration timeout) {
		return forIssuer(issuerUri, timeout, System::nanoTime);
	}

	/**
	 * {@link #forIssuer(String, Duration)} with another clock, so the tests run the production
	 * limits without sleeping through them.
	 *
	 * @param issuerUri the tenant's issuer as {@link Auth0Issuer} accepts it
	 * @param timeout limit for the connection and the response headers; half the limit of a fetch
	 * @param nanoClock monotonic clock in nanoseconds
	 * @return the source
	 */
	static Auth0JwkSource forIssuer(String issuerUri, Duration timeout, LongSupplier nanoClock) {
		Duration fetchDeadline = timeout.multipliedBy(2);
		return new Auth0JwkSource(
				jwkSetUrl(issuerUri),
				new DeadlineResourceRetriever(timeout, fetchDeadline, JWKS_SIZE_LIMIT_BYTES),
				MIN_FETCH_INTERVAL,
				KEYS_TIME_TO_LIVE,
				MAX_WAITING_REQUESTS,
				fetchDeadline,
				nanoClock);
	}

	/**
	 * {@code <issuer>.well-known/jwks.json}.
	 *
	 * @param issuerUri the issuer as {@link Auth0Issuer} accepts it (with the trailing slash);
	 *     {@code SecurityConfig#jwtDecoder} never gets here with any other value
	 * @return the JWKS URL
	 * @throws IllegalArgumentException if {@code issuerUri} is not a URL (a programming error)
	 */
	static URL jwkSetUrl(String issuerUri) {
		try {
			return URI.create(issuerUri + ".well-known/jwks.json").toURL();
		} catch (MalformedURLException | IllegalArgumentException e) {
			throw new IllegalArgumentException("Issuer nao validado: " + Auth0Issuer.forLog(issuerUri), e);
		}
	}

	@Override
	public List<JWK> get(JWKSelector jwkSelector, SecurityContext context) throws KeySourceException {
		State seen = state;
		if (seen.keys() != null) {
			List<JWK> matches = jwkSelector.select(seen.keys());
			if (!matches.isEmpty()) {
				if (nanoClock.getAsLong() - seen.fetchedAt() >= timeToLiveNanos) {
					refreshInBackground(seen);
				}
				return matches;
			}
		}
		State current = refresh(seen);
		if (current.keys() == null) {
			throw new KeySourceException("Chaves do Auth0 indisponiveis");
		}
		List<JWK> matches = jwkSelector.select(current.keys());
		if (matches.isEmpty() && current.lastAttemptFailed()) {
			throw new KeySourceException("Chave do token desconhecida e Auth0 indisponivel para confirmar");
		}
		return matches;
	}

	/**
	 * Makes {@code seen} as current as the rules allow and returns the state to answer from: fetches
	 * if this request may, waits for an in-flight fetch if there is a waiting slot, otherwise returns
	 * immediately.
	 */
	private State refresh(State seen) {
		if (fetchLock.tryLock()) {
			try {
				return fetchIfDue(seen);
			} finally {
				fetchLock.unlock();
			}
		}
		if (!waitingRequests.tryAcquire()) {
			return state;
		}
		try {
			if (fetchLock.tryLock(maxWait.toNanos(), TimeUnit.NANOSECONDS)) {
				fetchLock.unlock();
			}
			return state;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return state;
		} finally {
			waitingRequests.release();
		}
	}

	/** Called with {@link #fetchLock} held. */
	private State fetchIfDue(State seen) {
		State current = state;
		if (current != seen || !current.fetchDue(nanoClock.getAsLong(), minFetchIntervalNanos)) {
			return current;
		}
		State next;
		try {
			JWKSet keys = JWKSet.parse(retriever.retrieveResource(jwkSetUrl).getContent());
			next = State.fetched(keys, nanoClock.getAsLong());
			if (current.lastAttemptFailed()) {
				log.info("JWKS do Auth0 de novo disponivel ({})", jwkSetUrl);
			}
		} catch (Exception e) {
			next = current.failedAt(nanoClock.getAsLong());
			log.warn(
					"JWKS do Auth0 indisponivel ({}): {}. {}; nova tentativa daqui a pelo menos {} s",
					jwkSetUrl,
					e.toString(),
					current.keys() == null
							? "Pedidos protegidos respondem 500"
							: "Mantidas as chaves anteriores",
					Duration.ofNanos(minFetchIntervalNanos).toSeconds());
		}
		state = next;
		return next;
	}

	private void refreshInBackground(State seen) {
		if (!seen.fetchDue(nanoClock.getAsLong(), minFetchIntervalNanos)
				|| !backgroundRefreshRunning.compareAndSet(false, true)) {
			return;
		}
		Thread.ofVirtual()
				.name("auth0-jwks-refresh")
				.start(
						() -> {
							try {
								refresh(seen);
							} finally {
								backgroundRefreshRunning.set(false);
							}
						});
	}

	/**
	 * Immutable snapshot, replaced as a whole so a lookup never sees half an update.
	 *
	 * @param keys the last keys fetched successfully, {@code null} before the first success
	 * @param fetchedAt when {@code keys} were fetched
	 * @param attempted whether any fetch was attempted yet
	 * @param attemptEndedAt when the last attempt ended
	 * @param lastAttemptFailed whether the last attempt failed
	 */
	private record State(
			JWKSet keys, long fetchedAt, boolean attempted, long attemptEndedAt, boolean lastAttemptFailed) {

		static final State EMPTY = new State(null, 0, false, 0, false);

		static State fetched(JWKSet keys, long now) {
			return new State(keys, now, true, now, false);
		}

		State failedAt(long now) {
			return new State(keys, fetchedAt, true, now, true);
		}

		boolean fetchDue(long now, long minIntervalNanos) {
			return !attempted || now - attemptEndedAt >= minIntervalNanos;
		}
	}
}
