package pt.diamondcars.catalogbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import pt.diamondcars.catalogbackend.web.RateLimitInterceptor;

/**
 * The only {@link WebMvcConfigurer} that registers interceptors (TASK-002, AC D.4). Order matters
 * and is the registration order below: {@link ActiveUserInterceptor} first, so a deactivated user
 * is rejected before anything else runs, then {@link RateLimitInterceptor} on {@code /api/leads}
 * only (moved here unchanged from {@code CorsConfig}, which no longer is a {@code
 * WebMvcConfigurer}).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final ActiveUserInterceptor activeUserInterceptor;
	private final RateLimitInterceptor rateLimitInterceptor;

	/**
	 * Creates the configuration with the interceptors it registers.
	 *
	 * @param activeUserInterceptor rejects authenticated users whose profile is inactive
	 * @param rateLimitInterceptor per-IP limit for the public lead submission
	 */
	public WebConfig(ActiveUserInterceptor activeUserInterceptor, RateLimitInterceptor rateLimitInterceptor) {
		this.activeUserInterceptor = activeUserInterceptor;
		this.rateLimitInterceptor = rateLimitInterceptor;
	}

	/**
	 * Registers {@link ActiveUserInterceptor} on every path, then {@link RateLimitInterceptor} on
	 * {@code /api/leads}.
	 *
	 * @param registry Spring MVC's interceptor registry
	 */
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(activeUserInterceptor).addPathPatterns("/**");
		registry.addInterceptor(rateLimitInterceptor).addPathPatterns("/api/leads");
	}
}
