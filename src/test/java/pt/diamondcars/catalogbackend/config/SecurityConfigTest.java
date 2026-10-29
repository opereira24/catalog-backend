package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import pt.diamondcars.catalogbackend.config.support.SecurityProbeConfig;
import pt.diamondcars.catalogbackend.config.support.TestJwtDecoderConfig;
import pt.diamondcars.catalogbackend.config.support.TestJwtSupport;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link SecurityConfig} end to end through the real filter chain (TASK-002, AC E.2), with tokens
 * that are actually decoded by the offline HMAC decoder of {@link TestJwtDecoderConfig} and a
 * protected probe route from {@link SecurityProbeConfig}. No test here reaches Auth0.
 *
 * <p>MockMvc does not perform the {@code ERROR} dispatch to {@code /error}; the error statuses of
 * the public endpoints are covered in a real port by {@code PublicEndpointsHttpTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import({TestJwtDecoderConfig.class, SecurityProbeConfig.class})
@TestPropertySource(properties = "app.cors.allowed-origins=" + SecurityConfigTest.DC_ORIGIN + "," + SecurityConfigTest.DCBO_ORIGIN)
class SecurityConfigTest extends AbstractPostgresIntegrationTest {

	static final String DC_ORIGIN = "http://localhost:3000";
	static final String DCBO_ORIGIN = "http://localhost:3001";

	private static final String ISSUER = "https://oteustand.eu.auth0.com/";
	private static final String AUDIENCE = "https://oteustand.pt/api";

	@Autowired private MockMvc mockMvc;
	@Autowired private JsonMapper jsonMapper;

	@Test
	void requestWithoutTokenGets401WithApiErrorBodyAndBearerChallenge() throws Exception {
		mockMvc.perform(get(SecurityProbeConfig.PROBE_PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.error").value("Unauthorized"))
				.andExpect(jsonPath("$.message").value("Autenticacao necessaria"))
				.andExpect(jsonPath("$.path").value(SecurityProbeConfig.PROBE_PATH))
				.andExpect(jsonPath("$.timestamp").isNotEmpty());
	}

	/**
	 * The 401 body has the same {@code timestamp} format as the errors written by {@code
	 * ApiExceptionHandler}, because both go through the application's {@link JsonMapper}.
	 */
	@Test
	void unauthorizedTimestampHasTheSameFormatAsOtherApiErrors() throws Exception {
		String unauthorized = mockMvc.perform(get(SecurityProbeConfig.PROBE_PATH)).andReturn().getResponse().getContentAsString();
		String notFound =
				mockMvc.perform(get("/api/cars/00000000-0000-0000-0000-000000000001"))
						.andExpect(status().isNotFound())
						.andReturn()
						.getResponse()
						.getContentAsString();

		String pattern = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?([+-]\\d{2}:\\d{2}|Z)";
		assertThat(jsonMapper.readTree(unauthorized).get("timestamp").asString()).matches(pattern);
		assertThat(jsonMapper.readTree(notFound).get("timestamp").asString()).matches(pattern);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("rejectedTokens")
	void invalidTokenGets401(String description, String token) throws Exception {
		mockMvc.perform(get(SecurityProbeConfig.PROBE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
				.andExpect(jsonPath("$.message").value("Autenticacao necessaria"));
	}

	private static Stream<Arguments> rejectedTokens() {
		return Stream.of(
				Arguments.of(
						"audience de outra API",
						TestJwtSupport.signedTokenWithClaim(
								"auth0|x", List.of("https://another-api.example/"), TestJwtSupport.ROLES_CLAIM, List.of("admin"))),
				Arguments.of("assinado com outra chave", TestJwtSupport.tokenSignedWithAnotherKey("auth0|x")),
				Arguments.of("expirado", TestJwtSupport.expiredToken("auth0|x")),
				Arguments.of("lixo", "x.y.z"));
	}

	@Test
	void adminRoleClaimBecomesRoleAdminAndOpensTheAdminRoute() throws Exception {
		String token = TestJwtSupport.tokenWithRoles("auth0|admin", List.of("admin"));

		mockMvc.perform(get(SecurityProbeConfig.PROBE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasItem("ROLE_ADMIN")));
		mockMvc.perform(get(SecurityProbeConfig.ADMIN_PROBE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk());
	}

	@Test
	void userRoleOnTheAdminRouteGets403WithApiError() throws Exception {
		String token = TestJwtSupport.tokenWithRoles("auth0|user", List.of("user"));

		mockMvc.perform(get(SecurityProbeConfig.ADMIN_PROBE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.message").value("Autenticado, mas sem a role exigida para esta operacao"))
				.andExpect(jsonPath("$.path").value(SecurityProbeConfig.ADMIN_PROBE_PATH));
	}

	/**
	 * Absent and empty roles claim grant no {@code ROLE_*} at all. The list of authorities is not
	 * empty: Spring Security 7 always adds {@code FACTOR_BEARER} to a bearer-token authentication,
	 * so the assertion is on the {@code ROLE_} prefix.
	 *
	 * @param emptyClaim whether the claim is present as {@code []} instead of absent
	 */
	@ParameterizedTest(name = "claim vazio = {0}")
	@ValueSource(booleans = {false, true})
	void absentOrEmptyRolesClaimGrantsNoRole(boolean emptyClaim) throws Exception {
		String token =
				emptyClaim
						? TestJwtSupport.tokenWithRoles("auth0|norole", List.of())
						: TestJwtSupport.signedTokenWithClaim("auth0|norole", List.of(TestJwtSupport.VALID_AUDIENCE), null, null);

		MvcResult result =
				mockMvc.perform(get(SecurityProbeConfig.PROBE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
						.andExpect(status().isOk())
						.andReturn();
		List<String> authorities = List.of(jsonMapper.readValue(result.getResponse().getContentAsString(), String[].class));
		assertThat(authorities).noneMatch(authority -> authority.startsWith("ROLE_"));

		mockMvc.perform(get(SecurityProbeConfig.ADMIN_PROBE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isForbidden());
	}

	@Test
	void dcPreflightForCarsIsAllowed() throws Exception {
		assertPreflightAllowed(DC_ORIGIN, "/api/cars", "GET", null);
	}

	@Test
	void dcPreflightForLeadSubmissionIsAllowed() throws Exception {
		assertPreflightAllowed(DC_ORIGIN, "/api/leads", "POST", "content-type");
	}

	@ParameterizedTest
	@ValueSource(strings = {"PUT", "PATCH", "DELETE"})
	void dcboPreflightWithAuthorizationForProtectedRouteIsAllowed(String method) throws Exception {
		assertPreflightAllowed(DCBO_ORIGIN, SecurityProbeConfig.PROBE_PATH, method, "authorization,content-type");
	}

	@Test
	void preflightFromAnUnlistedOriginIsRejected() throws Exception {
		mockMvc.perform(
						options("/api/cars")
								.header(HttpHeaders.ORIGIN, "https://evil.example")
								.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
	}

	@Test
	void validatorAcceptsATokenOfTheConfiguredIssuerAndAudience() {
		OAuth2TokenValidator<Jwt> validator = SecurityConfig.validator(ISSUER, AUDIENCE);

		assertThat(validator.validate(jwt(ISSUER, List.of("https://other/", AUDIENCE), Instant.now().plusSeconds(300))).hasErrors())
				.isFalse();
	}

	@Test
	void validatorRejectsAnotherAudience() {
		OAuth2TokenValidator<Jwt> validator = SecurityConfig.validator(ISSUER, AUDIENCE);

		assertThat(validator.validate(jwt(ISSUER, List.of("https://another-api.example/"), Instant.now().plusSeconds(300))).hasErrors())
				.isTrue();
	}

	@Test
	void validatorRejectsAnotherTenant() {
		OAuth2TokenValidator<Jwt> validator = SecurityConfig.validator(ISSUER, AUDIENCE);

		assertThat(validator.validate(jwt("https://evil.eu.auth0.com/", List.of(AUDIENCE), Instant.now().plusSeconds(300))).hasErrors())
				.isTrue();
	}

	@Test
	void validatorRejectsAnExpiredToken() {
		OAuth2TokenValidator<Jwt> validator = SecurityConfig.validator(ISSUER, AUDIENCE);

		assertThat(validator.validate(jwt(ISSUER, List.of(AUDIENCE), Instant.now().minusSeconds(600))).hasErrors()).isTrue();
	}

	private void assertPreflightAllowed(String origin, String path, String method, String requestHeaders) throws Exception {
		var request =
				options(path).header(HttpHeaders.ORIGIN, origin).header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
		if (requestHeaders != null) {
			request.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requestHeaders);
		}
		var result =
				mockMvc.perform(request)
						.andExpect(status().isOk())
						.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
						.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString(method)))
						.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "1800"));
		if (requestHeaders != null) {
			for (String requestHeader : requestHeaders.split(",")) {
				result.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString(requestHeader)));
			}
		}
	}

	private static Jwt jwt(String issuer, List<String> audience, Instant expiresAt) {
		return Jwt.withTokenValue("token")
				.header("alg", "RS256")
				.issuer(issuer)
				.subject("auth0|someone")
				.audience(audience)
				.issuedAt(expiresAt.minusSeconds(900))
				.expiresAt(expiresAt)
				.build();
	}
}
