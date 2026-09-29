package pt.diamondcars.catalogbackend.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS policy for the public catalog API (TASK-014 requirement 6): allows the configured origin(s)
 * (the {@code dc} site) to call {@code /api/**} with {@code GET}/{@code POST}/{@code OPTIONS}, and
 * nothing else — {@code /internal/**} (TASK-016) is never a browser-called endpoint and is
 * deliberately left out of this mapping.
 *
 * <p>Never registers a wildcard origin combined with credentials: this API uses no cookies/session,
 * only explicit origins from {@code app.cors.allowed-origins} (comma-separated, default placeholder
 * {@code http://localhost:3000}).
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

	private final List<String> allowedOrigins;

	/**
	 * Creates the configuration with the allowed origins resolved from {@code
	 * app.cors.allowed-origins}.
	 *
	 * @param allowedOrigins explicit origins allowed to call {@code /api/**}
	 */
	public CorsConfig(@Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
		this.allowedOrigins = allowedOrigins;
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
}
