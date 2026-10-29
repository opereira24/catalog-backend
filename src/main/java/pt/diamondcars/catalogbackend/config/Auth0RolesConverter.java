package pt.diamondcars.catalogbackend.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Converts the namespaced Auth0 roles claim of an access token into Spring Security {@link
 * GrantedAuthority} instances, so that {@code @PreAuthorize("hasRole('ADMIN')")} works in the
 * controllers of later tasks without any of them touching {@link SecurityConfig}. Ported from
 * {@code dcbo-backend} (TASK-002, AC D.1).
 *
 * <p>The claim name comes from {@code app.auth0.roles-claim}, whose default is the production
 * claim {@code https://oteustand.pt/roles} injected by the Auth0 Action of the tenant. The values
 * ({@code admin}, {@code user}) are upper-cased and prefixed with {@code ROLE_}, matching what
 * {@code hasRole(...)} expects and what {@code AppUserRole} persists. An absent or empty claim, and
 * blank values inside it, grant nothing.
 */
@Component
public class Auth0RolesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

	private final String rolesClaim;

	/**
	 * Creates the converter bound to the configured roles claim name.
	 *
	 * @param rolesClaim name of the namespaced claim Auth0 uses for back-office roles, injected
	 *     from {@code app.auth0.roles-claim}
	 */
	public Auth0RolesConverter(@Value("${app.auth0.roles-claim}") String rolesClaim) {
		this.rolesClaim = rolesClaim;
	}

	/**
	 * Reads the roles claim of the given JWT and maps every non-blank value to a {@code
	 * ROLE_<VALUE>} authority, upper-cased.
	 *
	 * @param jwt the decoded, already-validated access token
	 * @return the resulting authorities, or an empty collection if the claim is absent or empty
	 */
	@Override
	public Collection<GrantedAuthority> convert(Jwt jwt) {
		List<String> roles = jwt.getClaimAsStringList(rolesClaim);
		if (roles == null || roles.isEmpty()) {
			return List.of();
		}
		return roles.stream()
				.filter(StringUtils::hasText)
				.map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)))
				.collect(Collectors.toUnmodifiableList());
	}
}
