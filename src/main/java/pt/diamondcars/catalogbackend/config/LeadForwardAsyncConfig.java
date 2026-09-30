package pt.diamondcars.catalogbackend.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Enables {@code @Async} and provides the dedicated, bounded executor {@code LeadForwarder} uses
 * to forward a newly-created lead off the site visitor's own request thread (TASK-015 review r1,
 * IMPORTANTE 5): before this, the {@code AFTER_COMMIT} forwarding attempt ran synchronously in
 * {@code afterCompletion}, still inside the request thread's call stack, so a slow or hanging
 * {@code dcbo-backend} could keep the visitor waiting for the full connect/read timeout (up to
 * ~6 s) before the 201 response was even written.
 *
 * <p>Kept as a small, dedicated pool (never the common/default {@code SimpleAsyncTaskExecutor},
 * which is unbounded) with a bounded queue: if forwarding attempts pile up faster than {@code
 * dcbo-backend} can be reached, {@link ThreadPoolExecutor.DiscardPolicy} silently drops the excess
 * submission instead of throwing back into the caller's (still request) thread — a dropped
 * submission simply leaves that lead's {@code forwardedAt} {@code null}, so {@code
 * LeadForwardRetryService}'s periodic retry picks it up on its next run exactly as if the
 * immediate forward attempt itself had failed.
 */
@Configuration
@EnableAsync
public class LeadForwardAsyncConfig {

	/**
	 * Bean name {@code LeadForwarder} injects by, via {@code @Async(BEAN_NAME)}, to disambiguate
	 * from any other {@link Executor} that might exist in the context.
	 */
	public static final String BEAN_NAME = "leadForwardExecutor";

	/**
	 * Builds the bounded executor bean.
	 *
	 * @return the configured executor, already initialized
	 */
	@Bean(BEAN_NAME)
	public Executor leadForwardExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(2);
		executor.setMaxPoolSize(4);
		executor.setQueueCapacity(50);
		executor.setThreadNamePrefix("lead-forward-");
		executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
		executor.initialize();
		return executor;
	}
}
