package pt.diamondcars.catalogbackend.config;

import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Gives the web layer and the services access to the identity of the currently authenticated
 * back-office user, so no service ever reads {@link SecurityContextHolder} itself. Ported from
 * {@code dcbo-backend} (TASK-002, AC D.2). Consumers: {@link ActiveUserInterceptor} here, and the
 * authorship/{@code /api/me} code of the back-office tasks that follow.
 *
 * <p>The identity is always the Auth0 {@code sub} claim of the request's JWT, the same value
 * {@link pt.diamondcars.catalogbackend.domain.user.AppUser#getAuthSubject()} is matched against.
 * On the public endpoints there is never a JWT authentication, even when the caller sends a token
 * (see {@link SecurityConfig}, the token is ignored there), so this returns empty.
 */
@Component
public class AuthenticatedUserProvider {

	/**
	 * Returns the {@code sub} claim of the JWT backing the current request's authentication, if
	 * any.
	 *
	 * @return the Auth0 subject of the currently authenticated user, or {@link Optional#empty()}
	 *     when there is no authenticated JWT in the current {@link SecurityContextHolder} (a call
	 *     outside an HTTP request, or to a public endpoint)
	 */
	public Optional<String> getCurrentUserSubject() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken jwtAuthenticationToken) {
			return Optional.ofNullable(jwtAuthenticationToken.getToken().getSubject());
		}
		return Optional.empty();
	}
}
