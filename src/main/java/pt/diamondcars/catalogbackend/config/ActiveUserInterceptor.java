package pt.diamondcars.catalogbackend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import pt.diamondcars.catalogbackend.domain.user.AppUserRepository;
import pt.diamondcars.catalogbackend.web.exception.InactiveUserException;

/**
 * Rejects, with 403, every request from an authenticated back-office user whose local {@code
 * app_users} profile has {@code active = false} (TASK-002, requirement 5, AC D.3), even with an
 * otherwise valid token. Ported from {@code dcbo-backend}; registered by {@link WebConfig} on
 * {@code /**}, ahead of every other interceptor.
 *
 * <p>Runs in {@link #preHandle}, which the {@code DispatcherServlet} invokes before the controller
 * method and therefore before the {@code @PreAuthorize} advice around it: a deactivated admin gets
 * "conta desativada", never "sem role".
 *
 * <p>Inert on the public endpoints and the actuator: the token is ignored there ({@link
 * SecurityConfig}), so there is no authenticated subject and no query. A subject with no row in
 * {@code app_users} is let through on purpose: the table starts empty and the first admin is
 * created later (TASK-007); only {@code active = false} rejects. Cost: one lookup by the unique
 * index {@code idx_app_users_auth_subject} per authenticated request.
 */
@Component
public class ActiveUserInterceptor implements HandlerInterceptor {

	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final AppUserRepository appUserRepository;

	/**
	 * Creates the interceptor with its collaborators.
	 *
	 * @param authenticatedUserProvider resolves the Auth0 {@code sub} of the current caller, if any
	 * @param appUserRepository looked up by {@code sub} to find the caller's local profile
	 */
	public ActiveUserInterceptor(
			AuthenticatedUserProvider authenticatedUserProvider, AppUserRepository appUserRepository) {
		this.authenticatedUserProvider = authenticatedUserProvider;
		this.appUserRepository = appUserRepository;
	}

	/**
	 * Rejects the request with {@link InactiveUserException} when the authenticated caller has a
	 * local profile and it is inactive; otherwise lets the request continue unchanged.
	 *
	 * @param request the incoming request
	 * @param response the response (unused: rejection is signalled by throwing, so {@code
	 *     ApiExceptionHandler} writes the same {@code ApiError} envelope as for every other error)
	 * @param handler the resolved handler (unused)
	 * @return {@code true} whenever it returns normally
	 */
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		authenticatedUserProvider
				.getCurrentUserSubject()
				.flatMap(appUserRepository::findByAuthSubject)
				.filter(appUser -> !appUser.isActive())
				.ifPresent(
						appUser -> {
							throw new InactiveUserException("Conta desativada: " + appUser.getAuthSubject());
						});
		return true;
	}
}
