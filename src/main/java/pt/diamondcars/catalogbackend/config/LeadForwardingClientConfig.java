package pt.diamondcars.catalogbackend.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Provides the {@link RestClient.Builder} {@code LeadForwarder} uses to call {@code
 * dcbo-backend}'s {@code POST /internal/leads} (TASK-015 requirements 5-6), configured with a
 * short, explicit connect/read timeout so a slow or unreachable {@code dcbo-backend} can never
 * hold the forwarding attempt open for long (requirement 6: "timeout curto, &le; 3 s").
 *
 * <p>Kept as a dedicated, qualified bean — {@link #BEAN_NAME} — instead of relying on Spring
 * Boot's own auto-configured default {@link RestClient.Builder}, so a test can bind a {@code
 * MockRestServiceServer} to exactly this instance ({@code LeadForwarderTest}) without affecting
 * any other {@link RestClient.Builder} that might exist in the context.
 */
@Configuration
public class LeadForwardingClientConfig {

	/**
	 * Bean name {@link pt.diamondcars.catalogbackend.service.LeadForwarder} (and tests) inject by,
	 * to disambiguate from Spring Boot's own auto-configured {@link RestClient.Builder}.
	 */
	public static final String BEAN_NAME = "leadForwardingRestClientBuilder";

	/**
	 * Connect and read timeout applied to every request issued through this client (requirement 6).
	 */
	private static final Duration TIMEOUT = Duration.ofSeconds(3);

	/**
	 * Builds the {@link RestClient.Builder} bean, pointed at {@code dcbo-backend} via {@code
	 * dcbo.sync.base-url}.
	 *
	 * @param baseUrl the base URL of {@code dcbo-backend}, from {@code dcbo.sync.base-url}
	 * @return the configured builder
	 */
	@Bean(BEAN_NAME)
	public RestClient.Builder leadForwardingRestClientBuilder(@Value("${dcbo.sync.base-url}") String baseUrl) {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(TIMEOUT);
		requestFactory.setReadTimeout(TIMEOUT);
		return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory);
	}
}
