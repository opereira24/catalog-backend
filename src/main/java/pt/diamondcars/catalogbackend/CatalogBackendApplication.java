package pt.diamondcars.catalogbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the {@code catalog-backend} Spring Boot application.
 *
 * <p>{@link EnableScheduling} activates the {@code @Scheduled} infrastructure {@code
 * pt.diamondcars.catalogbackend.web.RateLimitInterceptor#evictExpiredEntries} relies on: without
 * it the per-IP map of the {@code POST /api/leads} rate limiter is never swept and grows without
 * bound (fixed by {@code CatalogBackendApplicationTests}).
 */
@EnableScheduling
@SpringBootApplication
public class CatalogBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CatalogBackendApplication.class, args);
	}

}
