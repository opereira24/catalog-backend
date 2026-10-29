package pt.diamondcars.catalogbackend.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static pt.diamondcars.catalogbackend.support.WrittenTimestamps.assertUpdatedAtIsTheNewlyWrittenOne;
import static pt.diamondcars.catalogbackend.support.WrittenTimestamps.makeStale;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import pt.diamondcars.catalogbackend.domain.user.AppUser;
import pt.diamondcars.catalogbackend.domain.user.AppUserRepository;
import pt.diamondcars.catalogbackend.domain.user.AppUserRole;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end tests of {@link AppUserController}, {@code AppUserService}, {@code
 * ActiveUserInterceptor} and {@link ApiExceptionHandler} through the real filter chain (TASK-007,
 * AC F.2), with {@link MockMvc} against the shared PostgreSQL container. #1 to #16 are the port of
 * {@code dcbo-backend}'s {@code AppUserControllerTest} under {@code /api/backoffice}; #17 to #27 are
 * new. Same context configuration as {@code NotificationControllerTest}, so the cached context is
 * reused. The races are in {@code service/AppUserConcurrencyTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class AppUserControllerTest extends AbstractPostgresIntegrationTest {

	private static final String USERS = "/api/backoffice/users";
	private static final String ME = "/api/backoffice/me";
	private static final String ADMIN_ROLE = "ROLE_ADMIN";
	private static final String USER_ROLE = "ROLE_USER";
	private static final String FORBIDDEN_MESSAGE = "Autenticado, mas sem a role exigida para esta operacao";
	private static final String NO_PROFILE_MESSAGE = "Sem perfil local para o utilizador autenticado";

	/** The 8 keys of AC A.3. A literal, so a component added or renamed by accident fails here. */
	private static final List<String> USER_KEYS =
			List.of("id", "authSubject", "email", "name", "role", "active", "createdAt", "updatedAt");

	@Autowired private MockMvc mockMvc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private AppUserRepository appUserRepository;
	@Autowired private JdbcTemplate jdbcTemplate;

	/**
	 * Every test starts with an empty {@code app_users} (the first-admin tests need it empty) and
	 * leaves it empty: an inactive profile left behind would turn another class's requests with the
	 * same {@code sub} into 403.
	 */
	@BeforeEach
	@AfterEach
	void cleanDatabase() {
		jdbcTemplate.update("DELETE FROM app_users");
	}

	// ---------------------------------------------------------------------------------------------
	// Port of dcbo-backend (#1 to #16)
	// ---------------------------------------------------------------------------------------------

	/** #1. @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void adminListsUsersReturns200WithThePaginatedList() throws Exception {
		aProfile("auth0|list-1", AppUserRole.USER, true);
		aProfile("auth0|list-2", AppUserRole.ADMIN, true);

		mockMvc.perform(get(USERS).with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.page.totalElements").value(2));
	}

	/** #2. @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void nonAdminListingUsersReturns403() throws Exception {
		mockMvc.perform(get(USERS).with(jwt().authorities(new SimpleGrantedAuthority(USER_ROLE))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(FORBIDDEN_MESSAGE));
	}

	/**
	 * #3 (AC B.1): every {@code /users} route, including a {@code DELETE} of an id that does not
	 * exist (403, never 404: the role is checked before the service), is 403 for {@code USER} and for
	 * a token without a role, and changes nothing. Kills {@code hasAnyRole('ADMIN', 'USER')} on the
	 * class (mutation 5 of AC F.5).
	 *
	 * @param description label of the case
	 * @param token {@code USER} or no role
	 * @param method HTTP method
	 * @param pathTemplate path, {@code %s} standing for the target id
	 * @param body JSON body, or {@code null}
	 * @param unknownId when {@code true}, the path uses an id without a profile
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("adminOnlyRoutesForEveryNonAdminToken")
	void nonAdminCallingAnyUsersEndpointReturns403(
			String description, RequestPostProcessor token, String method, String pathTemplate, String body,
			boolean unknownId) throws Exception {
		AppUser target = aProfile("auth0|role-check-target", AppUserRole.USER, true);
		String path = String.format(pathTemplate, unknownId ? UUID.randomUUID() : target.getId());

		mockMvc.perform(withBody(request(method, path), body).with(token))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(FORBIDDEN_MESSAGE));

		AppUser unchanged = appUserRepository.findById(target.getId()).orElseThrow();
		assertThat(unchanged.getName()).isEqualTo(target.getName());
		assertThat(unchanged.getRole()).isEqualTo(AppUserRole.USER);
		assertThat(unchanged.isActive()).isTrue();
		assertThat(appUserRepository.count()).isEqualTo(1);
	}

	static Stream<Arguments> adminOnlyRoutesForEveryNonAdminToken() {
		List<Object[]> routes = List.of(
				new Object[] {"GET", USERS, null, false},
				new Object[] {"GET", USERS + "/%s", null, false},
				new Object[] {"POST", USERS, "{\"authSubject\":\"auth0|role-check-post\","
						+ "\"email\":\"role-check@diamondcars.pt\",\"name\":\"Role Check\",\"role\":\"user\"}", false},
				new Object[] {"PUT", USERS + "/%s", "{\"name\":\"Nome Alterado\",\"role\":\"admin\"}", false},
				new Object[] {"PATCH", USERS + "/%s/active", "{\"active\":false}", false},
				new Object[] {"DELETE", USERS + "/%s", null, false},
				new Object[] {"DELETE", USERS + "/%s", null, true});
		return Stream.of("ROLE_USER", "sem role").flatMap(label -> routes.stream().map(route -> Arguments.of(
				label + ": " + route[0] + " " + route[1] + (Boolean.TRUE.equals(route[3]) ? " (id inexistente)" : ""),
				label.equals("ROLE_USER") ? token("auth0|user-caller", USER_ROLE) : withoutRole("auth0|no-role-caller"),
				route[0], route[1], route[2], route[3])));
	}

	/**
	 * #4: 201 with {@code Location: /api/backoffice/users/<id>}, a body with exactly the 8 keys of
	 * AC A.3 and the timestamps written by the {@code INSERT} (AC C.2, {@code saveAndFlush}), and the
	 * profile reads back from the {@code Location}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void createsAUserAndReadsItBackById() throws Exception {
		MockHttpServletResponse response = mockMvc.perform(json(post(USERS), newUser("auth0|new-user")).with(admin()))
				.andExpect(status().isCreated())
				.andReturn()
				.getResponse();
		JsonNode created = objectMapper.readTree(response.getContentAsString());
		String id = created.get("id").asString();

		assertThat(response.getHeader("Location")).isEqualTo("/api/backoffice/users/" + id);
		assertThat(new ArrayList<>(created.propertyNames())).containsExactlyInAnyOrderElementsOf(USER_KEYS);
		assertThat(created.get("authSubject").asString()).isEqualTo("auth0|new-user");
		assertThat(created.get("email").asString()).isEqualTo("novo@diamondcars.pt");
		assertThat(created.get("name").asString()).isEqualTo("Novo Utilizador");
		assertThat(created.get("role").asString()).isEqualTo("user");
		assertThat(created.get("active").asBoolean()).isTrue();
		assertThat(created.get("createdAt").isNull()).as("createdAt").isFalse();
		assertThat(created.get("updatedAt").isNull()).as("updatedAt").isFalse();

		mockMvc.perform(get(response.getHeader("Location")).with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id))
				.andExpect(jsonPath("$.email").value("novo@diamondcars.pt"));
	}

	/** #5 (AC C.2). @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void creatingAUserWithADuplicateAuthSubjectReturns409() throws Exception {
		aProfile("auth0|duplicate", AppUserRole.USER, true);

		mockMvc.perform(json(post(USERS), newUser("auth0|duplicate")).with(admin()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.message").value(
						"Ja existe um perfil registado para este authSubject: auth0|duplicate"));
		assertThat(appUserRepository.count()).isEqualTo(1);
	}

	/**
	 * #6: a role outside {@code admin}/{@code user}, the upper-case constant name included, is a 400
	 * naming the field.
	 *
	 * @param role the invalid role
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@ParameterizedTest(name = "role={0}")
	@ValueSource(strings = {"superadmin", "ADMIN"})
	void rejectsAUserWithAnInvalidRole(String role) throws Exception {
		String body = "{\"authSubject\":\"auth0|bad-role\",\"email\":\"bad@diamondcars.pt\","
				+ "\"name\":\"Mau Role\",\"role\":\"" + role + "\"}";

		mockMvc.perform(json(post(USERS), body).with(admin()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(startsWith("role: ")));
		assertThat(appUserRepository.count()).isZero();
	}

	/** #7 (AC C.3). @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void updatingAUserReplacesItsNameAndRole() throws Exception {
		AppUser user = aProfile("auth0|to-update", AppUserRole.USER, true);

		mockMvc.perform(json(put(USERS + "/" + user.getId()), "{\"name\":\"Nome Atualizado\",\"role\":\"admin\"}")
						.with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Nome Atualizado"))
				.andExpect(jsonPath("$.role").value("admin"));

		AppUser updated = appUserRepository.findById(user.getId()).orElseThrow();
		assertThat(updated.getName()).isEqualTo("Nome Atualizado");
		assertThat(updated.getRole()).isEqualTo(AppUserRole.ADMIN);
	}

	/**
	 * TASK-020 B.2: the 200 of a {@code PUT} with a new name carries the new {@code updatedAt}, not the
	 * one the profile had before (dirty checking alone flushed only at commit, after the response was
	 * mapped).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void updatedUserCarriesTheNewUpdatedAt() throws Exception {
		AppUser user = aProfile("auth0|stale-update", AppUserRole.USER, true);
		makeStale(jdbcTemplate, "app_users", user.getId());

		String body = mockMvc.perform(json(put(USERS + "/" + user.getId()), "{\"name\":\"Nome Novo\",\"role\":\"user\"}")
						.with(admin()))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertUpdatedAtIsTheNewlyWrittenOne(objectMapper.readTree(body), jdbcTemplate, "app_users");
	}

	/**
	 * TASK-020 B.2: the 200 of a {@code PATCH .../active} that deactivates another admin's active
	 * profile carries the new {@code updatedAt}.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void activationChangeCarriesTheNewUpdatedAt() throws Exception {
		AppUser user = aProfile("auth0|stale-active", AppUserRole.USER, true);
		makeStale(jdbcTemplate, "app_users", user.getId());

		String body = mockMvc.perform(json(patch(USERS + "/" + user.getId() + "/active"), "{\"active\":false}")
						.with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false))
				.andReturn()
				.getResponse()
				.getContentAsString();

		assertUpdatedAtIsTheNewlyWrittenOne(objectMapper.readTree(body), jdbcTemplate, "app_users");
	}

	/**
	 * #8: a user deactivated through the endpoint is refused on the rest of the back-office and on
	 * their own profile. Correction of the port (AC F.2): {@code dcbo-backend} checked with {@code GET
	 * /api/cars}, which here is public and ignores the token (200 either way).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void deactivatingAUserBlocksFurtherAuthenticatedRequests() throws Exception {
		AppUser user = aProfile("auth0|to-deactivate", AppUserRole.USER, true);
		RequestPostProcessor theUser = token("auth0|to-deactivate", USER_ROLE);
		mockMvc.perform(get("/api/backoffice/cars").with(theUser)).andExpect(status().isOk());

		mockMvc.perform(json(patch(USERS + "/" + user.getId() + "/active"), "{\"active\":false}").with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));

		for (String path : List.of("/api/backoffice/cars", ME)) {
			mockMvc.perform(get(path).with(theUser))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.message").value("Conta desativada: auth0|to-deactivate"));
		}
		assertThat(appUserRepository.findById(user.getId()).orElseThrow().isActive()).isFalse();
	}

	/** #9: same correction as #8. @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void reactivatingAUserRestoresAccess() throws Exception {
		AppUser user = aProfile("auth0|to-reactivate", AppUserRole.USER, false);
		RequestPostProcessor theUser = token("auth0|to-reactivate", USER_ROLE);
		mockMvc.perform(get("/api/backoffice/cars").with(theUser)).andExpect(status().isForbidden());

		mockMvc.perform(json(patch(USERS + "/" + user.getId() + "/active"), "{\"active\":true}").with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true));

		mockMvc.perform(get("/api/backoffice/cars").with(theUser)).andExpect(status().isOk());
		mockMvc.perform(get(ME).with(theUser)).andExpect(status().isOk());
	}

	/** #10. @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void getMeReturnsTheCallersOwnProfile() throws Exception {
		AppUser self = aProfile("auth0|self", AppUserRole.ADMIN, true);
		aProfile("auth0|someone-else", AppUserRole.USER, true);

		mockMvc.perform(get(ME).with(token("auth0|self", ADMIN_ROLE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(self.getId().toString()))
				.andExpect(jsonPath("$.authSubject").value("auth0|self"))
				.andExpect(jsonPath("$.role").value("admin"));
	}

	/**
	 * #11 (AC C.7): 404 in the usual {@link pt.diamondcars.catalogbackend.web.dto.ApiError} envelope,
	 * not a body-less 404.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void getMeWithoutALocalProfileReturns404() throws Exception {
		mockMvc.perform(get(ME).with(token("auth0|no-profile", USER_ROLE)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.error").value("Not Found"))
				.andExpect(jsonPath("$.message").value(NO_PROFILE_MESSAGE))
				.andExpect(jsonPath("$.path").value(ME));
	}

	/** #12 (AC C.5). @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void adminCannotDeleteTheirOwnProfile() throws Exception {
		AppUser self = aProfile("auth0|self-delete", AppUserRole.ADMIN, true);

		mockMvc.perform(delete(USERS + "/" + self.getId()).with(token("auth0|self-delete", ADMIN_ROLE)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Nao e possivel eliminar o proprio perfil: " + self.getId()));

		assertThat(appUserRepository.existsById(self.getId())).isTrue();
	}

	/** #13. @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void adminCanDeleteAnotherUsersProfile() throws Exception {
		aProfile("auth0|admin-caller", AppUserRole.ADMIN, true);
		AppUser other = aProfile("auth0|other", AppUserRole.USER, true);

		mockMvc.perform(delete(USERS + "/" + other.getId()).with(admin()))
				.andExpect(status().isNoContent());

		assertThat(appUserRepository.existsById(other.getId())).isFalse();
		assertThat(appUserRepository.count()).isEqualTo(1);
	}

	/** #14 (AC C.4). @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void adminCannotDeactivateTheirOwnProfile() throws Exception {
		AppUser self = aProfile("auth0|self-deactivate", AppUserRole.ADMIN, true);

		mockMvc.perform(json(patch(USERS + "/" + self.getId() + "/active"), "{\"active\":false}")
						.with(token("auth0|self-deactivate", ADMIN_ROLE)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value("Nao e possivel desativar o proprio perfil: " + self.getId()));

		assertThat(appUserRepository.findById(self.getId()).orElseThrow().isActive()).isTrue();
	}

	/** #15. @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void adminCanDeactivateAnotherAdminsProfile() throws Exception {
		aProfile("auth0|admin-caller", AppUserRole.ADMIN, true);
		AppUser other = aProfile("auth0|other-admin", AppUserRole.ADMIN, true);

		mockMvc.perform(json(patch(USERS + "/" + other.getId() + "/active"), "{\"active\":false}").with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));

		assertThat(appUserRepository.findById(other.getId()).orElseThrow().isActive()).isFalse();
	}

	/** #16. @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void listingUsersWithoutATokenIsUnauthorized() throws Exception {
		mockMvc.perform(get(USERS)).andExpect(status().isUnauthorized());
	}

	// ---------------------------------------------------------------------------------------------
	// New (#17 to #27)
	// ---------------------------------------------------------------------------------------------

	/**
	 * #17 (AC D.1, D.2): with {@code app_users} empty, the {@code admin} role of the token alone lets
	 * the first admin create another user's profile and their own; from then on {@code /me} is 200.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void firstAdminCanCreateUsersWithoutALocalProfile() throws Exception {
		assertThat(appUserRepository.count()).isZero();
		RequestPostProcessor firstAdmin = token("auth0|first-admin", ADMIN_ROLE);

		mockMvc.perform(json(post(USERS), newUser("auth0|first-colleague")).with(firstAdmin))
				.andExpect(status().isCreated());
		mockMvc.perform(json(post(USERS), "{\"authSubject\":\"auth0|first-admin\",\"email\":\"admin@diamondcars.pt\","
								+ "\"name\":\"Primeiro Admin\",\"role\":\"admin\"}")
						.with(firstAdmin))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.role").value("admin"));

		mockMvc.perform(get(ME).with(firstAdmin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authSubject").value("auth0|first-admin"))
				.andExpect(jsonPath("$.role").value("admin"));
		assertThat(appUserRepository.count()).isEqualTo(2);
	}

	/** #18 (AC D.3): 404, not 401 nor 403. @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void firstAdminGets404OnMeWithNoLocalProfile() throws Exception {
		mockMvc.perform(get(ME).with(token("auth0|first-admin", ADMIN_ROLE)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(NO_PROFILE_MESSAGE));
	}

	/**
	 * #19 (AC B.2): a token without a role reads its own profile, and a {@code sub} without a profile
	 * gets 404, never 403. Kills removing {@code @PreAuthorize} from {@code me()} (it would inherit
	 * the class's {@code ADMIN}).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void meIsOpenToATokenWithoutARole() throws Exception {
		aProfile("auth0|no-role", AppUserRole.USER, true);

		mockMvc.perform(get(ME).with(withoutRole("auth0|no-role")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authSubject").value("auth0|no-role"));
		mockMvc.perform(get(ME).with(withoutRole("auth0|no-role-no-profile")))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(NO_PROFILE_MESSAGE));
	}

	/** #20 (AC B.2). @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void meIsOpenToTheUserRole() throws Exception {
		aProfile("auth0|plain-user", AppUserRole.USER, true);

		mockMvc.perform(get(ME).with(token("auth0|plain-user", USER_ROLE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authSubject").value("auth0|plain-user"))
				.andExpect(jsonPath("$.role").value("user"));
	}

	/** #21. @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void meWithoutATokenIsUnauthorized() throws Exception {
		mockMvc.perform(get(ME))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Autenticacao necessaria"));
	}

	/**
	 * #22 (AC C.4): {@code active} missing or {@code null} is a 400 naming the field, never a
	 * default that deactivates the user.
	 *
	 * @param body the request body
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"{}", "{\"active\":null}"})
	void settingActiveWithoutTheFieldIs400(String body) throws Exception {
		AppUser user = aProfile("auth0|keeps-active", AppUserRole.USER, true);

		mockMvc.perform(json(patch(USERS + "/" + user.getId() + "/active"), body).with(admin()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(startsWith("active: ")));

		assertThat(appUserRepository.findById(user.getId()).orElseThrow().isActive()).isTrue();
	}

	/**
	 * #23 (AC C.3, A.3): the {@code PUT} changes only {@code name} and {@code role}; {@code
	 * authSubject}, {@code email} and {@code active} in the body are ignored.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void updateIgnoresAuthSubjectEmailAndActiveInTheBody() throws Exception {
		AppUser user = aProfile("auth0|identity-kept", AppUserRole.USER, true);

		mockMvc.perform(json(put(USERS + "/" + user.getId()), "{\"name\":\"Outro Nome\",\"role\":\"admin\","
								+ "\"authSubject\":\"auth0|hijacked\",\"email\":\"hijacked@example.com\",\"active\":false}")
						.with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authSubject").value("auth0|identity-kept"))
				.andExpect(jsonPath("$.active").value(true));

		AppUser updated = appUserRepository.findById(user.getId()).orElseThrow();
		assertThat(updated.getName()).isEqualTo("Outro Nome");
		assertThat(updated.getRole()).isEqualTo(AppUserRole.ADMIN);
		assertThat(updated.getAuthSubject()).isEqualTo("auth0|identity-kept");
		assertThat(updated.getEmail()).isEqualTo("auth0|identity-kept@diamondcars.pt");
		assertThat(updated.isActive()).isTrue();
	}

	/** #24 (AC A.3). @throws Exception propagated from {@link MockMvc#perform} */
	@Test
	void createIgnoresActiveInTheBody() throws Exception {
		mockMvc.perform(json(post(USERS), "{\"authSubject\":\"auth0|born-active\",\"email\":\"a@diamondcars.pt\","
								+ "\"name\":\"Nasce Ativo\",\"role\":\"user\",\"active\":false}")
						.with(admin()))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.active").value(true));

		assertThat(appUserRepository.findByAuthSubject("auth0|born-active").orElseThrow().isActive()).isTrue();
	}

	/**
	 * #25: an id without a profile is a 404 naming it on {@code GET}, {@code PUT}, {@code PATCH} and
	 * {@code DELETE}.
	 *
	 * @param method HTTP method
	 * @param suffix path after the id
	 * @param body JSON body, or {@code null}
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("routesWithAnId")
	void unknownIdIs404OnGetPutPatchAndDelete(String method, String suffix, String body) throws Exception {
		UUID unknown = UUID.randomUUID();

		mockMvc.perform(withBody(request(method, USERS + "/" + unknown + suffix), body).with(admin()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("Utilizador nao encontrado: " + unknown));
	}

	static Stream<Arguments> routesWithAnId() {
		return Stream.of(
				Arguments.of("GET", "", null),
				Arguments.of("PUT", "", "{\"name\":\"Nome\",\"role\":\"user\"}"),
				Arguments.of("PATCH", "/active", "{\"active\":false}"),
				Arguments.of("DELETE", "", null));
	}

	/**
	 * #26 (AC C.1, TASK-013 convention): the listing ends in {@code id} DESC with the default sort
	 * and with a requested one. Three profiles tied on {@code name} and {@code created_at}, inserted
	 * by SQL in ascending {@code id}: without the tie-breaker PostgreSQL returns the heap order {@code
	 * 01, 02, 03}, with it {@code 03, 02, 01}. Kills removing {@code StableSort.withIdTiebreak}.
	 *
	 * @param sort the {@code sort} parameter, or {@code null} for the controller default
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@ParameterizedTest(name = "sort={0}")
	@NullSource
	@ValueSource(strings = "name,asc")
	void listingHasIdAsLastTiebreakerWithAndWithoutARequestedSort(String sort) throws Exception {
		Timestamp createdAt = Timestamp.from(Instant.parse("2026-10-01T12:00:00Z"));
		List<String> ascendingIds = List.of(
				"00000000-0000-0000-0000-000000000001",
				"00000000-0000-0000-0000-000000000002",
				"00000000-0000-0000-0000-000000000003");
		for (String id : ascendingIds) {
			jdbcTemplate.update(
					"INSERT INTO app_users (id, auth_subject, email, name, role, active, created_at, updated_at)"
							+ " VALUES (?::uuid, ?, 'igual@diamondcars.pt', 'Nome Igual', 'user', true, ?, ?)",
					id, "auth0|tie-" + id, createdAt, createdAt);
		}
		jdbcTemplate.execute("ANALYZE app_users");

		List<String> paged = new ArrayList<>();
		for (int page = 0; page < ascendingIds.size(); page++) {
			MockHttpServletRequestBuilder request = get(USERS).param("size", "1").param("page", String.valueOf(page));
			if (sort != null) {
				request.param("sort", sort);
			}
			String response = mockMvc.perform(request.with(admin()))
					.andExpect(status().isOk())
					.andReturn()
					.getResponse()
					.getContentAsString();
			objectMapper.readTree(response).get("content").forEach(user -> paged.add(user.get("id").asString()));
		}

		assertThat(paged).containsExactlyElementsOf(ascendingIds.reversed());
	}

	/**
	 * #27 (AC B.3): the role that authorizes is the token's, never {@code app_users.role}. A profile
	 * {@code admin} with a {@code USER} token is refused; a profile {@code user} with an {@code ADMIN}
	 * token is let through.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void theProfileRoleNeverGrantsAccessOnlyTheTokenRoleDoes() throws Exception {
		aProfile("auth0|profile-admin", AppUserRole.ADMIN, true);
		aProfile("auth0|profile-user", AppUserRole.USER, true);

		mockMvc.perform(get(USERS).with(token("auth0|profile-admin", USER_ROLE)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(FORBIDDEN_MESSAGE));
		mockMvc.perform(get(USERS).with(token("auth0|profile-user", ADMIN_ROLE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2));
	}

	// ---------------------------------------------------------------------------------------------
	// Helpers
	// ---------------------------------------------------------------------------------------------

	private AppUser aProfile(String authSubject, AppUserRole role, boolean active) {
		return appUserRepository.saveAndFlush(AppUser.builder()
				.authSubject(authSubject)
				.email(authSubject + "@diamondcars.pt")
				.name("Utilizador " + authSubject)
				.role(role)
				.active(active)
				.build());
	}

	private static String newUser(String authSubject) {
		return "{\"authSubject\":\"" + authSubject + "\",\"email\":\"novo@diamondcars.pt\","
				+ "\"name\":\"Novo Utilizador\",\"role\":\"user\"}";
	}

	/** An admin whose {@code sub} has no profile unless a test creates one. */
	private static RequestPostProcessor admin() {
		return token("auth0|admin-caller", ADMIN_ROLE);
	}

	private static RequestPostProcessor token(String subject, String role) {
		return jwt().jwt(jwt -> jwt.subject(subject)).authorities(new SimpleGrantedAuthority(role));
	}

	/** What Spring Security 7 gives a bearer token without the roles claim. */
	private static RequestPostProcessor withoutRole(String subject) {
		return jwt().jwt(jwt -> jwt.subject(subject))
				.authorities(new SimpleGrantedAuthority("FACTOR_BEARER"), new SimpleGrantedAuthority("SCOPE_openid"));
	}

	private static MockHttpServletRequestBuilder request(String method, String path) {
		return switch (method) {
			case "GET" -> get(path);
			case "POST" -> post(path);
			case "PUT" -> put(path);
			case "PATCH" -> patch(path);
			case "DELETE" -> delete(path);
			default -> throw new IllegalArgumentException("Metodo nao suportado: " + method);
		};
	}

	private static MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder request, String body) {
		return body == null ? request : json(request, body);
	}

	private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
		return request.contentType(MediaType.APPLICATION_JSON).content(body);
	}
}
