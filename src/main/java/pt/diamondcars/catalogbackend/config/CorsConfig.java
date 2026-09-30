package pt.diamondcars.catalogbackend.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import pt.diamondcars.catalogbackend.web.RateLimitInterceptor;

/**
 * Servlet-level web configuration shared by the whole public API: CORS policy (TASK-014
 * requirement 6) and, since TASK-015, registration of {@link RateLimitInterceptor} for {@code
 * POST /api/leads}.
 *
 * <p>CORS allows the configured origin(s) (the {@code dc} site) to call {@code /api/**} with
 * {@code GET}/{@code POST}/{@code OPTIONS}, and nothing else — {@code /internal/**} (TASK-016) is
 * never a browser-called endpoint and is deliberately left out of this mapping. Never registers a
 * wildcard origin combined with credentials: this API uses no cookies/session, only explicit
 * origins from {@code app.cors.allowed-origins} (comma-separated, default placeholder {@code
 * http://localhost:3000}).
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

	private final List<String> allowedOrigins;
	private final RateLimitInterceptor rateLimitInterceptor;

	/**
	 * Creates the configuration with the allowed origins resolved from {@code
	 * app.cors.allowed-origins} and the rate limiter TASK-015 adds for {@code POST /api/leads}.
	 *
	 * @param allowedOrigins explicit origins allowed to call {@code /api/**}
	 * @param rateLimitInterceptor the interceptor registered below for {@code /api/leads}
	 */
	public CorsConfig(
			@Value("${app.cors.allowed-origins}") List<String> allowedOrigins,
			RateLimitInterceptor rateLimitInterceptor) {
		this.allowedOrigins = allowedOrigins;
		this.rateLimitInterceptor = rateLimitInterceptor;
	}

	/**
	 * Restricts the CORS mapping to {@code /api/**}, leaving {@code /internal/**} and {@code
	 * /actuator/**} with no CORS headers at all (they are never called from a browser).
	 *
	 * @param registry the registry to add the mapping to
	 */
	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry
				.addMapping("/api/**")
				.allowedOrigins(allowedOrigins.toArray(new String[0]))
				.allowedMethods("GET", "POST", "OPTIONS");
	}

	/**
	 * Registers {@link RateLimitInterceptor} for {@code /api/leads} only (TASK-015 requirement 4)
	 * — every other endpoint, including the rest of {@code /api/**}, is unaffected.
	 *
	 * @param registry the registry to add the interceptor to
	 */
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/leads");
	}
}
