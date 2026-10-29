package pt.diamondcars.catalogbackend.config;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * The closed list of requests that need no token (TASK-002, AC C.4): the single source of truth
 * for {@link SecurityConfig} (what is {@code permitAll()}, and where an {@code Authorization}
 * header is ignored) and for {@code PublicSurfaceTest}, which fails if any controller mapping
 * other than the ones below becomes reachable through this matcher.
 *
 * <p>Every {@code /api/**} entry is bound to an explicit method, never to "any method": {@code POST
 * /api/cars} or {@code DELETE /api/cars/{id}} can become back-office endpoints and must stay
 * protected. {@code HEAD} is listed next to every {@code GET} because Spring MVC serves {@code HEAD}
 * through the same handlers and the public site answered it with 200 before security existed;
 * without it {@code HEAD} would turn into 401.
 *
 * <p>Consequence for later tasks: any back-office {@code GET} with a single path segment under
 * {@code /api/cars/} (for example {@code /api/cars/stats}) would be public through {@code
 * /api/cars/{id}}. Back-office routes live in their own path space; {@code PublicSurfaceTest}
 * guards it.
 */
public final class PublicEndpoints {

	private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

	/** Matches exactly the public requests listed in the class Javadoc. */
	public static final RequestMatcher MATCHER =
			new OrRequestMatcher(
					PATHS.matcher(HttpMethod.GET, "/api/cars"),
					PATHS.matcher(HttpMethod.HEAD, "/api/cars"),
					PATHS.matcher(HttpMethod.GET, "/api/cars/highlights"),
					PATHS.matcher(HttpMethod.HEAD, "/api/cars/highlights"),
					PATHS.matcher(HttpMethod.GET, "/api/cars/{id}"),
					PATHS.matcher(HttpMethod.HEAD, "/api/cars/{id}"),
					PATHS.matcher(HttpMethod.POST, "/api/leads"),
					PATHS.matcher("/actuator/health"),
					PATHS.matcher("/actuator/health/**"),
					PATHS.matcher("/actuator/info"));

	private PublicEndpoints() {}
}
