package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.lead.LeadOrigin;
import pt.diamondcars.catalogbackend.domain.lead.LeadRepository;
import pt.diamondcars.catalogbackend.domain.lead.LeadStatus;
import pt.diamondcars.catalogbackend.domain.notification.Notification;
import pt.diamondcars.catalogbackend.domain.notification.NotificationRepository;
import pt.diamondcars.catalogbackend.domain.user.AppUser;
import pt.diamondcars.catalogbackend.domain.user.AppUserRepository;
import pt.diamondcars.catalogbackend.domain.user.AppUserRole;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end tests of {@link NotificationController} and {@code NotificationService} through the
 * real filter chain (TASK-006, AC E.1, E.3, E.4), with {@link MockMvc} against the shared PostgreSQL
 * container. Same context configuration as {@code TransactionControllerTest}, so the cached context
 * is reused. The races (a notification removed by the cascade of its lead) are in {@code
 * service/BackOfficeLeadConcurrencyTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class NotificationControllerTest extends AbstractPostgresIntegrationTest {

	private static final String PATH = "/api/backoffice/notifications";
	private static final String INACTIVE_SUBJECT = "auth0|inactive-notifications-test";

	/** The 8 keys of AC E.3. A literal, so a component added or renamed by accident fails here. */
	private static final List<String> NOTIFICATION_KEYS =
			List.of("id", "tipo", "titulo", "mensagem", "prioridade", "leadId", "read", "createdAt");

	/** Fixed ids in the order they are inserted (TASK-013): the heap order of a three-way tie. */
	private static final List<String> ASCENDING_IDS = List.of(
			"00000000-0000-0000-0000-000000000001",
			"00000000-0000-0000-0000-000000000002",
			"00000000-0000-0000-0000-000000000003");

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private NotificationRepository notificationRepository;
	@Autowired private LeadRepository leadRepository;
	@Autowired private AppUserRepository appUserRepository;
	@Autowired private JdbcTemplate jdbcTemplate;
	@Autowired private EntityManagerFactory entityManagerFactory;

	/** Clears every row this class writes (shared container, no per-test rollback). */
	@BeforeEach
	void cleanDatabase() {
		notificationRepository.deleteAllInBatch();
		leadRepository.deleteAllInBatch();
	}

	@AfterEach
	void removeTheInactiveProfile() {
		appUserRepository.findAll().stream()
				.filter(user -> INACTIVE_SUBJECT.equals(user.getAuthSubject()))
				.forEach(appUserRepository::delete);
	}

	// ---------------------------------------------------------------------------------------------
	// AC E.1: authorization
	// ---------------------------------------------------------------------------------------------

	/** @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void withoutATokenEveryRouteIs401() throws Exception {
		for (Supplier<MockHttpServletRequestBuilder> route : routes(UUID.randomUUID())) {
			mockMvc.perform(route.get()).andExpect(status().isUnauthorized());
		}
	}

	/**
	 * A token without a role is 403 on every route, and changes nothing.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aTokenWithoutARoleIsForbidden() throws Exception {
		Notification notification = saved(unread());

		for (Supplier<MockHttpServletRequestBuilder> route : routes(notification.getId())) {
			mockMvc.perform(route.get().with(withoutRole()))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.message").value("Autenticado, mas sem a role exigida para esta operacao"));
		}
		assertThat(notificationRepository.findById(notification.getId()).orElseThrow().isRead()).isFalse();
	}

	/** @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void anInactiveUserIsForbidden() throws Exception {
		appUserRepository.saveAndFlush(AppUser.builder()
				.authSubject(INACTIVE_SUBJECT)
				.email("inativo@example.com")
				.name("Inativo")
				.role(AppUserRole.USER)
				.active(false)
				.build());

		mockMvc.perform(post(PATH + "/read-all")
						.with(jwt().jwt(token -> token.subject(INACTIVE_SUBJECT)).authorities(new SimpleGrantedAuthority("ROLE_USER"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Conta desativada: " + INACTIVE_SUBJECT));
	}

	/**
	 * Both roles use every route.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void userAndAdminUseEveryRoute() throws Exception {
		for (RequestPostProcessor role : List.of(user(), admin())) {
			Notification notification = saved(unread());
			mockMvc.perform(get(PATH).with(role)).andExpect(status().isOk());
			mockMvc.perform(patch(PATH + "/{id}/read", notification.getId()).with(role)).andExpect(status().isOk());
			mockMvc.perform(post(PATH + "/read-all").with(role)).andExpect(status().isNoContent());
			mockMvc.perform(delete(PATH + "/{id}", notification.getId()).with(role)).andExpect(status().isNoContent());
		}
	}

	// ---------------------------------------------------------------------------------------------
	// AC E.3: listing
	// ---------------------------------------------------------------------------------------------

	/**
	 * Newest first by default, 50 per page, exactly the 8 keys; {@code leadId} is the lead's id.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listsNewestFirstWithExactlyTheEightKeys() throws Exception {
		Lead lead = aLead();
		Notification older = saved(unread().lead(lead));
		Notification newer = saved(unread());
		setCreatedAt(older.getId(), "2026-01-01T10:00:00Z");
		setCreatedAt(newer.getId(), "2026-02-01T10:00:00Z");

		JsonNode listing = getJson(get(PATH));

		assertThat(listing.get("page").get("size").asInt()).isEqualTo(50);
		assertThat(ids(listing)).containsExactly(newer.getId().toString(), older.getId().toString());
		JsonNode withLead = listing.get("content").get(1);
		assertThat(new ArrayList<>(withLead.propertyNames())).containsExactlyInAnyOrderElementsOf(NOTIFICATION_KEYS);
		assertThat(withLead.get("leadId").asString()).isEqualTo(lead.getId().toString());
		assertThat(withLead.get("read").asBoolean()).isFalse();
	}

	/**
	 * TASK-013 convention: three notifications tied on the requested sort come back {@code id} DESC.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aClientRequestedSortHasIdAsLastTiebreaker() throws Exception {
		for (String id : ASCENDING_IDS) {
			jdbcTemplate.update(
					"INSERT INTO notifications (id, tipo, mensagem, created_at) VALUES (?::uuid, 'aviso', 'Igual', ?)",
					id, Timestamp.from(Instant.parse("2026-03-10T09:00:00Z")));
		}
		jdbcTemplate.execute("ANALYZE notifications");

		List<String> paged = new ArrayList<>();
		for (int page = 0; page < ASCENDING_IDS.size(); page++) {
			paged.addAll(ids(getJson(get(PATH).param("sort", "tipo,asc").param("size", "1").param("page", String.valueOf(page)))));
		}

		assertThat(paged).containsExactlyElementsOf(ASCENDING_IDS.reversed());
	}

	/**
	 * {@code ?read=true|false} filters; empty means no filter; anything else is 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void filtersByRead() throws Exception {
		Notification unread = saved(unread());
		Notification read = saved(unread().read(true));

		assertThat(ids(getJson(get(PATH).param("read", "false")))).containsExactly(unread.getId().toString());
		assertThat(ids(getJson(get(PATH).param("read", "true")))).containsExactly(read.getId().toString());
		assertThat(ids(getJson(get(PATH).param("read", "")))).hasSize(2);
		mockMvc.perform(get(PATH).param("read", "abc").with(user()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Parametro 'read' com valor invalido"));
	}

	/**
	 * {@code sort} only takes the scalar properties; {@code lead.nome} (a join over the {@code LAZY}
	 * lead) is 400.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void sortOnlyAcceptsTheScalarProperties() throws Exception {
		for (String property : List.of("tipo", "titulo", "prioridade", "read", "createdAt", "id")) {
			mockMvc.perform(get(PATH).param("sort", property).with(user())).andExpect(status().isOk());
		}
		for (String property : List.of("lead.nome", "lead", "mensagem")) {
			mockMvc.perform(get(PATH).param("sort", property).with(user()))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.message").value("Campo de ordenacao desconhecido: " + property));
		}
	}

	/**
	 * AC E.4: 50 notifications with a lead are at most 3 statements; the lead is never loaded.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void listingFiftyNotificationsWithLeadRunsAtMostThreeStatements() throws Exception {
		Lead lead = aLead();
		List<Notification> notifications = new ArrayList<>();
		for (int i = 0; i < 50; i++) {
			notifications.add(unread().lead(lead).build());
		}
		notificationRepository.saveAllAndFlush(notifications);

		Statistics statistics = statistics();
		long statements;
		JsonNode listing;
		try {
			listing = getJson(get(PATH));
			statements = statistics.getPrepareStatementCount();
		} finally {
			statistics.setStatisticsEnabled(false);
		}

		assertThat(listing.get("content")).hasSize(50);
		assertThat(statements).isPositive().isLessThanOrEqualTo(3);
	}

	// ---------------------------------------------------------------------------------------------
	// AC E.3: writes
	// ---------------------------------------------------------------------------------------------

	/**
	 * {@code PATCH /{id}/read} answers the notification as stored, read; a second call answers the
	 * same (idempotent); only that notification changes.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void marksOneAsReadIdempotently() throws Exception {
		Lead lead = aLead();
		Notification target = saved(unread().lead(lead));
		Notification other = saved(unread());

		for (int call = 0; call < 2; call++) {
			mockMvc.perform(patch(PATH + "/{id}/read", target.getId()).with(user()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.id").value(target.getId().toString()))
					.andExpect(jsonPath("$.read").value(true))
					.andExpect(jsonPath("$.leadId").value(lead.getId().toString()))
					.andExpect(jsonPath("$.mensagem").value("Novo lead de Ana"));
		}
		assertThat(notificationRepository.findById(other.getId()).orElseThrow().isRead()).isFalse();
	}

	/** @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void markingAnUnknownNotificationAsReadIs404() throws Exception {
		UUID unknown = UUID.randomUUID();
		mockMvc.perform(patch(PATH + "/{id}/read", unknown).with(user()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Notificacao nao encontrada: " + unknown));
	}

	/**
	 * {@code POST /read-all} is 204 and marks every unread one; with 20 unread it is at most 2
	 * statements ({@code ActiveUserInterceptor} and one {@code UPDATE}), never one per row.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void marksAllAsReadInOneStatement() throws Exception {
		List<Notification> notifications = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			notifications.add(unread().build());
		}
		notificationRepository.saveAllAndFlush(notifications);

		Statistics statistics = statistics();
		long statements;
		try {
			mockMvc.perform(post(PATH + "/read-all").with(user())).andExpect(status().isNoContent());
			statements = statistics.getPrepareStatementCount();
		} finally {
			statistics.setStatisticsEnabled(false);
		}

		assertThat(statements).isPositive().isLessThanOrEqualTo(2);
		assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE NOT read", Long.class)).isZero();
	}

	/**
	 * {@code POST /read-all}, not {@code PATCH} (the verb of {@code dcbo-backend}).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void readAllIsAPostAndNotAPatch() throws Exception {
		mockMvc.perform(patch(PATH + "/read-all").with(user())).andExpect(status().isMethodNotAllowed());
	}

	/**
	 * {@code DELETE} is 204 and removes only that notification (the lead stays); a second one is 404.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void deletesOneNotificationAndKeepsItsLead() throws Exception {
		Lead lead = aLead();
		Notification target = saved(unread().lead(lead));
		Notification other = saved(unread());

		mockMvc.perform(delete(PATH + "/{id}", target.getId()).with(user())).andExpect(status().isNoContent());

		assertThat(notificationRepository.findAll()).extracting(Notification::getId).containsExactly(other.getId());
		assertThat(leadRepository.existsById(lead.getId())).isTrue();
		mockMvc.perform(delete(PATH + "/{id}", target.getId()).with(user()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Notificacao nao encontrada: " + target.getId()));
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------

	private List<Supplier<MockHttpServletRequestBuilder>> routes(UUID id) {
		return List.of(
				() -> get(PATH),
				() -> patch(PATH + "/{id}/read", id),
				() -> post(PATH + "/read-all"),
				() -> delete(PATH + "/{id}", id));
	}

	private Statistics statistics() {
		Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();
		return statistics;
	}

	private Lead aLead() {
		return leadRepository.saveAndFlush(
				Lead.builder().nome("Ana").telefone("912345678").status(LeadStatus.ATIVO).origem(LeadOrigin.WEBSITE).build());
	}

	private static Notification.NotificationBuilder unread() {
		return Notification.builder().tipo("novo_lead").titulo("Novo Lead").mensagem("Novo lead de Ana").prioridade("media");
	}

	private Notification saved(Notification.NotificationBuilder notification) {
		return notificationRepository.saveAndFlush(notification.build());
	}

	private void setCreatedAt(UUID id, String instant) {
		jdbcTemplate.update("UPDATE notifications SET created_at = ? WHERE id = ?", Timestamp.from(Instant.parse(instant)), id);
	}

	private JsonNode getJson(MockHttpServletRequestBuilder request) throws Exception {
		String response = mockMvc.perform(request.with(user()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response);
	}

	private static List<String> ids(JsonNode listing) {
		List<String> ids = new ArrayList<>();
		listing.get("content").forEach(notification -> ids.add(notification.get("id").asString()));
		return ids;
	}

	private static RequestPostProcessor user() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private static RequestPostProcessor admin() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	/** A bearer token with no role: what Spring Security 7 builds when the roles claim is empty. */
	private static RequestPostProcessor withoutRole() {
		return jwt().authorities(new SimpleGrantedAuthority("FACTOR_BEARER"), new SimpleGrantedAuthority("SCOPE_openid"));
	}
}
