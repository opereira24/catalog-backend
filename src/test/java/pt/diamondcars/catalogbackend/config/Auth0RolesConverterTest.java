package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import pt.diamondcars.catalogbackend.config.support.TestJwtSupport;

/**
 * Unit test of {@link Auth0RolesConverter} (ported from {@code dcbo-backend}, TASK-002 AC E.1): no
 * Spring context, only the claim-to-authority mapping.
 */
class Auth0RolesConverterTest {

	private static final String ROLES_CLAIM = TestJwtSupport.ROLES_CLAIM;

	private final Auth0RolesConverter converter = new Auth0RolesConverter(ROLES_CLAIM);

	@Test
	void mapsEachRolesClaimValueToAnUppercaseRoleAuthority() {
		Jwt jwt = jwtWithClaimValue(List.of("admin", "user"));

		assertThat(converter.convert(jwt))
				.containsExactlyInAnyOrder(new SimpleGrantedAuthority("ROLE_ADMIN"), new SimpleGrantedAuthority("ROLE_USER"));
	}

	@Test
	void returnsNoAuthoritiesWhenTheRolesClaimIsAbsent() {
		Jwt jwt = jwtWithClaimValue(null);

		assertThat(converter.convert(jwt)).isEmpty();
	}

	/**
	 * Every shape the claim could take, including the ones that only exercise {@code
	 * filter(hasText)} (null and blank values inside the list).
	 *
	 * @param description label of the case
	 * @param claimValue the raw claim value
	 * @param expectedAuthorities what the converter must produce
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("rolesClaimShapes")
	void mapsEveryShapeOfTheRolesClaimWithoutThrowing(
			String description, Object claimValue, List<GrantedAuthority> expectedAuthorities) {
		Jwt jwt = jwtWithClaimValue(claimValue);

		assertThat(converter.convert(jwt)).containsExactlyInAnyOrderElementsOf(expectedAuthorities);
	}

	private static Stream<Arguments> rolesClaimShapes() {
		return Stream.of(
				Arguments.of("lista vazia", List.of(), List.of()),
				Arguments.of("string simples em vez de lista", "admin", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))),
				Arguments.of(
						"lista com valores nulos/em branco (exercita o filter(hasText))",
						Arrays.asList("admin", null, "  "),
						List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))),
				Arguments.of(
						"objeto em vez de string/lista",
						Map.of("role", "admin"),
						List.of(new SimpleGrantedAuthority("ROLE_{ROLE=ADMIN}"))),
				Arguments.of(
						"numeros em vez de strings",
						List.of(1, 2),
						List.of(new SimpleGrantedAuthority("ROLE_1"), new SimpleGrantedAuthority("ROLE_2"))));
	}

	private static Jwt jwtWithClaimValue(Object claimValue) {
		Jwt.Builder builder =
				Jwt.withTokenValue("token")
						.header("alg", "none")
						.subject("auth0|test-user")
						.issuedAt(Instant.now())
						.expiresAt(Instant.now().plusSeconds(60));
		if (claimValue != null) {
			builder.claim(ROLES_CLAIM, claimValue);
		}
		return builder.build();
	}
}
