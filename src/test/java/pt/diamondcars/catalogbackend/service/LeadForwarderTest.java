package pt.diamondcars.catalogbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * {@code app.leads.forward.retry.enabled=false}, inherited from {@link
 * AbstractPostgresIntegrationTest} (requirement 7: "tem de estar desligado por defeito em
 * testes"; review r1, IMPORTANTE 4); this test invokes {@link
 * LeadForwardRetryService#retryPendingForwards()} directly instead, as the acceptance criterion
 * requires.
 */
@SpringBootTest
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

	/**
	 * TASK-015 review r1, BLOQUEADOR 1: a 400 response from {@code dcbo-backend} (the shape of
	 * response a payload it rejects as invalid would produce) is treated as a permanent failure —
	 * the lead's {@code forwardAttempts} is set to {@link
	 * LeadForwardOutcomeRecorder#PERMANENT_FAILURE_ATTEMPTS} in a single attempt, and a second
	 * retry run makes no further HTTP call at all.
	 */
	@Test
	void retryTreatsA400AsPermanentAndNeverRetriesTheSameLeadAgain() {
		Lead pending =
				leadRepository.saveAndFlush(Lead.builder().nome("Invalido").telefone("944444444").build());

		dcboBackend.expect(requestTo("http://localhost:8080/internal/leads")).andRespond(withBadRequest());

		leadForwardRetryService.retryPendingForwards();

		Lead afterFirstAttempt = leadRepository.findById(pending.getId()).orElseThrow();
		assertThat(afterFirstAttempt.getForwardedAt()).isNull();
		assertThat(afterFirstAttempt.getForwardAttempts())
				.isEqualTo(LeadForwardOutcomeRecorder.PERMANENT_FAILURE_ATTEMPTS);

		// No new expectation registered on dcboBackend: a second retry run attempting any HTTP call
		// at all would fail this verification.
		leadForwardRetryService.retryPendingForwards();
		dcboBackend.verify();
	}

	/**
	 * TASK-015 review r1, IMPORTANTE 1: the attempt that pushes a lead's {@code forwardAttempts} to
	 * the configured ceiling (default 5) logs at {@code ERROR}, not just {@code WARN} — so a lead
	 * abandoned after exhausting every retry never disappears with nothing louder than the same log
	 * level every ordinary transient failure already produces.
	 */
	@Test
	void logsAnErrorWhenTheLastAllowedAttemptStillFails() {
		Lead almostExhausted =
				leadRepository.saveAndFlush(
						Lead.builder().nome("Quase Esgotado").telefone("955555555").forwardAttempts(4).build());

		dcboBackend.expect(requestTo("http://localhost:8080/internal/leads")).andRespond(withServerError());

		Logger leadForwarderLogger = (Logger) LoggerFactory.getLogger(LeadForwarder.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		leadForwarderLogger.addAppender(appender);

		try {
			leadForwardRetryService.retryPendingForwards();
		} finally {
			leadForwarderLogger.detachAppender(appender);
		}

		assertThat(appender.list)
				.anySatisfy(
						event -> {
							assertThat(event.getLevel()).isEqualTo(Level.ERROR);
							assertThat(event.getFormattedMessage()).contains("abandoned");
						});

		Lead reloaded = leadRepository.findById(almostExhausted.getId()).orElseThrow();
		assertThat(reloaded.getForwardAttempts()).isEqualTo(5);
	}
}
