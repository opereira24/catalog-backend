package pt.diamondcars.catalogbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import pt.diamondcars.catalogbackend.config.LeadForwardingClientConfig;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.lead.LeadRepository;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * Tests {@link LeadForwardRetryService}'s periodic-retry logic (TASK-015 requirement 7) directly
 * — not through {@code PublicLeadController} — against a real PostgreSQL container ({@link
 * AbstractPostgresIntegrationTest}, TASK-002) and a {@link MockRestServiceServer} standing in for
 * {@code dcbo-backend}, never a real HTTP call.
 *
 * <p>{@code LeadForwardScheduler} itself (the {@code @Scheduled} cron trigger) is disabled via
 * {@code app.leads.forward.retry.enabled=false} below (requirement 7: "tem de estar desligado
 * por defeito em testes"); this test invokes {@link LeadForwardRetryService#retryPendingForwards()}
 * directly instead, as the acceptance criterion requires.
 */
@SpringBootTest(properties = "app.leads.forward.retry.enabled=false")
class LeadForwarderTest extends AbstractPostgresIntegrationTest {

	@Autowired private LeadRepository leadRepository;
	@Autowired private LeadForwardRetryService leadForwardRetryService;

	@Autowired
	@Qualifier(LeadForwardingClientConfig.BEAN_NAME)
	private RestClient.Builder leadForwardingRestClientBuilder;

	private MockRestServiceServer dcboBackend;

	/**
	 * Clears every lead written by a previous test and rebinds a fresh {@link
	 * MockRestServiceServer}, so tests never influence each other on the shared, JVM-wide
	 * container/context ({@link AbstractPostgresIntegrationTest}).
	 */
	@BeforeEach
	void setUp() {
		leadRepository.deleteAll();
		dcboBackend = MockRestServiceServer.bindTo(leadForwardingRestClientBuilder).build();
	}

	/**
	 * Acceptance criterion: invoking the scheduled retry directly marks {@code forwardedAt} on a
	 * pending lead once the simulated {@code dcbo-backend} responds 200.
	 */
	@Test
	void retryMarksForwardedAtWhenTheSimulatedServerRespondsOk() {
		Lead pending =
				leadRepository.saveAndFlush(
						Lead.builder().nome("Pendente").telefone("911111111").build());

		dcboBackend
				.expect(requestTo("http://localhost:8080/internal/leads"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("X-Internal-Token", "placeholder"))
				.andRespond(withSuccess());

		leadForwardRetryService.retryPendingForwards();

		dcboBackend.verify();
		Lead reloaded = leadRepository.findById(pending.getId()).orElseThrow();
		assertThat(reloaded.getForwardedAt()).isNotNull();
	}

	/**
	 * Acceptance criterion (implicit in requirement 7): a lead already forwarded is never retried.
	 */
	@Test
	void retrySkipsLeadsAlreadyForwarded() {
		leadRepository.saveAndFlush(
				Lead.builder()
						.nome("Ja Encaminhado")
						.telefone("922222222")
						.forwardedAt(java.time.OffsetDateTime.now())
						.build());

		// No expectation registered on dcboBackend at all: any request would fail verification.
		leadForwardRetryService.retryPendingForwards();

		dcboBackend.verify();
	}

	/**
	 * Acceptance criterion (requirement 7): a lead that has already reached the configured {@code
	 * app.leads.forward.max-attempts} is skipped by the retry, so a permanently-failing forward
	 * does not retry forever.
	 */
	@Test
	void retrySkipsLeadsThatReachedTheMaxAttemptCeiling() {
		leadRepository.saveAndFlush(
				Lead.builder().nome("Esgotado").telefone("933333333").forwardAttempts(5).build());

		// No expectation registered: default app.leads.forward.max-attempts is 5, so this lead
		// (already at 5 attempts) must be skipped without any HTTP call being attempted.
		leadForwardRetryService.retryPendingForwards();

		dcboBackend.verify();
	}
}
