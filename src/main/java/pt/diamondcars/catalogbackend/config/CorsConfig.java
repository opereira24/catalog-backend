package pt.diamondcars.catalogbackend.config;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * The single CORS policy of the application (TASK-002, AC D.5), applied by {@link SecurityConfig}
 * through {@code http.cors(...)}. There is deliberately no Spring MVC {@code addCorsMappings}
 * any more: two policies would drift, and only the one inside the security filter chain answers a
 * preflight (which never carries {@code Authorization}) before authentication rejects it.
 *
 * <p>Registered on {@code /api/**} only, as before (the actuator is never called from a browser).
 * Origins come from {@code app.cors.allowed-origins} ({@code CORS_ALLOWED_ORIGINS}, comma-separated:
 * the {@code dc} site and the {@code dcbo} back-office), never {@code *}, and credentials are never
 * allowed (there are no cookies, only the bearer token). Methods cover the public site ({@code GET},
 * {@code POST}) and the back-office ({@code PUT}, {@code PATCH}, {@code DELETE}); headers cover
 * {@code Content-Type} (the only one the {@code dc} sends) and {@code Authorization}. The preflight
 * is cached by the browser for 30 minutes, the value Spring MVC used implicitly before.
 */
@Configuration
public class CorsConfig {

	private static final List<String> ALLOWED_METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
	private static final List<String> ALLOWED_HEADERS = List.of("Authorization", "Content-Type");
	private static final Duration PREFLIGHT_MAX_AGE = Duration.ofMinutes(30);

	/**
	 * Declares the CORS policy described in the class Javadoc.
	 *
	 * @param allowedOrigins explicit origins allowed to call {@code /api/**}, from {@code
	 *     app.cors.allowed-origins}
	 * @return the configuration source {@link SecurityConfig} plugs into the filter chain
	 */
	@Bean
	public CorsConfigurationSource corsConfigurationSource(
			@Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(allowedOrigins);
		configuration.setAllowedMethods(ALLOWED_METHODS);
		configuration.setAllowedHeaders(ALLOWED_HEADERS);
		configuration.setMaxAge(PREFLIGHT_MAX_AGE);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", configuration);
		return source;
	}
}
