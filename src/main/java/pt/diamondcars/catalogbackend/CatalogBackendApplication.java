package pt.diamondcars.catalogbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the {@code catalog-backend} Spring Boot application.
 *
 * <p>{@link EnableScheduling} activates the {@code @Scheduled} infrastructure {@code
 * pt.diamondcars.catalogbackend.service.LeadForwardScheduler} (TASK-015 requirement 7) relies on
 * for its periodic lead-forwarding retry.
 */
@EnableScheduling
@SpringBootApplication
public class CatalogBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(CatalogBackendApplication.class, args);
	}

}
