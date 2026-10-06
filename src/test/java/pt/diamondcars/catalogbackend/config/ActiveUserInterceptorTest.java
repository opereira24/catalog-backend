package pt.diamondcars.catalogbackend.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import pt.diamondcars.catalogbackend.config.support.SecurityProbeConfig;
import pt.diamondcars.catalogbackend.config.support.TestJwtDecoderConfig;
import pt.diamondcars.catalogbackend.config.support.TestJwtSupport;
import pt.diamondcars.catalogbackend.domain.user.AppUser;
import pt.diamondcars.catalogbackend.domain.user.AppUserRepository;
import pt.diamondcars.catalogbackend.domain.user.AppUserRole;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * {@link ActiveUserInterceptor} through the real chain, against real {@code app_users} rows
 * (TASK-002, AC E.3). Same context as {@code SecurityConfigTest} (same imports and properties), so
 * no extra Spring context is started.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({TestJwtDecoderConfig.class, SecurityProbeConfig.class})
@TestPropertySource(properties = "app.cors.allowed-origins=" + SecurityConfigTest.DC_ORIGIN + "," + SecurityConfigTest.DCBO_ORIGIN)
class ActiveUserInterceptorTest extends AbstractPostgresIntegrationTest {

	private static final String SUBJECT = "auth0|interceptor-test";
	private static final String INACTIVE_MESSAGE = "Conta desativada: " + SUBJECT;

	@Autowired private MockMvc mockMvc;
	@Autowired private AppUserRepository appUserRepository;

	@AfterEach
	void cleanUp() {
		appUserRepository.deleteAll();
	}

	@Test
	void inactiveAdminIsRejectedWith403() throws Exception {
		saveProfile(AppUserRole.ADMIN, false);

		mockMvc.perform(get(SecurityProbeConfig.ADMIN_PROBE_PATH).header(HttpHeaders.AUTHORIZATION, bearer(List.of("admin"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.message").value(INACTIVE_MESSAGE))
				.andExpect(jsonPath("$.path").value(SecurityProbeConfig.ADMIN_PROBE_PATH));
	}

	/** A user without the admin role gets "conta desativada", not "sem role": the interceptor runs
	 * before {@code @PreAuthorize}. */
	@Test
	void inactiveUserIsRejectedBeforeTheRoleCheck() throws Exception {
		saveProfile(AppUserRole.USER, false);

		mockMvc.perform(get(SecurityProbeConfig.ADMIN_PROBE_PATH).header(HttpHeaders.AUTHORIZATION, bearer(List.of("user"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(INACTIVE_MESSAGE));
	}

	@Test
	void activeProfileIsLetThrough() throws Exception {
		saveProfile(AppUserRole.ADMIN, true);

		mockMvc.perform(get(SecurityProbeConfig.ADMIN_PROBE_PATH).header(HttpHeaders.AUTHORIZATION, bearer(List.of("admin"))))
				.andExpect(status().isOk());
	}

	/** {@code app_users} starts empty and the first admin is created later (TASK-007). */
	@Test
	void subjectWithoutProfileIsLetThrough() throws Exception {
		mockMvc.perform(get(SecurityProbeConfig.ADMIN_PROBE_PATH).header(HttpHeaders.AUTHORIZATION, bearer(List.of("admin"))))
				.andExpect(status().isOk());
	}

	/** The token is ignored on the public endpoints, so an inactive user sees them like a visitor. */
	@Test
	void inactiveUserWithTokenStillSeesThePublicCatalog() throws Exception {
		saveProfile(AppUserRole.ADMIN, false);

		mockMvc.perform(get("/api/cars").header(HttpHeaders.AUTHORIZATION, bearer(List.of("admin"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isArray());
	}

	private void saveProfile(AppUserRole role, boolean active) {
		appUserRepository.save(
				AppUser.builder()
						.authSubject(SUBJECT)
						.email("interceptor@example.com")
						.name("Interceptor Test")
						.role(role)
						.active(active)
						.build());
	}

	private static String bearer(List<String> roles) {
		return "Bearer " + TestJwtSupport.tokenWithRoles(SUBJECT, roles);
	}
}
