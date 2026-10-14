package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * Guard of the back-office convention (TASK-003, AC A.4), sibling of {@link PublicSurfaceTest}:
 * walks every controller mapping, and every {@code /api/**} route and method that {@link
 * PublicEndpoints#MATCHER} does <b>not</b> let through without a token must (a) live under {@code
 * /api/backoffice/} and (b) carry {@code @PreAuthorize}, on the method or on the class, and (c) its
 * effective expression (the method's, else the class's) must be {@code hasRole('ADMIN')} or {@code
 * hasAnyRole('ADMIN', 'USER')}, except exactly {@code GET}/{@code HEAD /api/backoffice/me}, which must
 * be {@code isAuthenticated()} (TASK-007 AC F.4). This is what fails when a back-office task adds a
 * route outside the prefix, or a controller with only the "authenticated" check (a token without a
 * role would pass), with or without an annotation that says so.
 *
 * <p>Same context as the public tests; never imports {@code SecurityProbeConfig} (its {@code
 * /api/security-probe} routes would rightly fail rule (a)).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class BackOfficeSurfaceTest extends AbstractPostgresIntegrationTest {

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	/** The role expressions a back-office route may have (rule (c)), exactly as written. */
	private static final Set<String> ROLE_EXPRESSIONS = Set.of("hasRole('ADMIN')", "hasAnyRole('ADMIN', 'USER')");

	/** The only routes that are "authenticated, any role or none" (TASK-007 AC B.2): the caller's own profile. */
	private static final Set<String> AUTHENTICATED_ONLY_ROUTES =
			Set.of("GET /api/backoffice/me", "HEAD /api/backoffice/me");

	private static final String AUTHENTICATED_ONLY = "isAuthenticated()";

	@Test
	void everyNonPublicApiRouteIsUnderTheBackOfficePrefixAndHasPreAuthorize() {
		List<String> outsideThePrefix = new ArrayList<>();
		List<String> withoutPreAuthorize = new ArrayList<>();
		List<String> withoutARoleExpression = new ArrayList<>();
		Map<String, String> authenticatedOnly = new TreeMap<>();
		List<String> backOfficeRoutes = new ArrayList<>();
		for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
			HandlerMethod handler = entry.getValue();
			for (PathPattern pattern : entry.getKey().getPathPatternsCondition().getPatterns()) {
				String patternString = pattern.getPatternString();
				if (!patternString.startsWith("/api/")) {
					continue;
				}
				String path = patternString.replaceAll("\\{[^}]+}", "x");
				for (RequestMethod method : acceptedMethods(entry.getKey())) {
					if (PublicEndpoints.MATCHER.matches(new MockHttpServletRequest(method.name(), path))) {
						continue;
					}
					String route = method + " " + patternString;
					backOfficeRoutes.add(route);
					if (!patternString.startsWith("/api/backoffice/")) {
						outsideThePrefix.add(route);
					}
					if (!hasPreAuthorize(handler)) {
						withoutPreAuthorize.add(route);
					}
					String expression = effectivePreAuthorize(handler);
					if (AUTHENTICATED_ONLY_ROUTES.contains(route)) {
						authenticatedOnly.put(route, expression);
					} else if (!ROLE_EXPRESSIONS.contains(expression)) {
						withoutARoleExpression.add(route + " -> " + expression);
					}
				}
			}
		}

		assertThat(outsideThePrefix).as("non-public /api routes outside /api/backoffice/").isEmpty();
		assertThat(withoutPreAuthorize).as("non-public /api routes without @PreAuthorize").isEmpty();
		// Rule (c), TASK-007 AC F.4: (b) only checks that @PreAuthorize exists, so
		// @PreAuthorize("isAuthenticated()") anywhere passed it, and that is the "authenticated only"
		// rule (b) exists to forbid. The effective expression (the method's, else the class's) must
		// be a role, except on the caller's own profile, which must be exactly isAuthenticated().
		assertThat(withoutARoleExpression)
				.as("back-office routes whose effective @PreAuthorize is not one of " + ROLE_EXPRESSIONS)
				.isEmpty();
		assertThat(authenticatedOnly)
				.as("the routes open to any authenticated caller, and their effective @PreAuthorize")
				.containsOnlyKeys(AUTHENTICATED_ONLY_ROUTES)
				.allSatisfy((route, expression) -> assertThat(expression).as(route).isEqualTo(AUTHENTICATED_ONLY));
		// Positive control: the walk does see the back-office handlers of each task that added some
		// (an empty walk proves nothing): cars (TASK-003), transactions and finances (TASK-005),
		// leads and notifications (TASK-006), users and me (TASK-007).
		assertThat(backOfficeRoutes)
				.contains(
						"GET /api/backoffice/cars",
						"GET /api/backoffice/cars/{id}",
						"POST /api/backoffice/cars",
						"POST /api/backoffice/cars/{id}/reserve",
						"POST /api/backoffice/cars/{id}/release-reservation",
						"PATCH /api/backoffice/cars/{id}/highlight",
						"GET /api/backoffice/transactions",
						"GET /api/backoffice/transactions/{id}",
						"POST /api/backoffice/transactions",
						"PUT /api/backoffice/transactions/{id}",
						"DELETE /api/backoffice/transactions/{id}",
						"GET /api/backoffice/finances/summary",
						"GET /api/backoffice/leads",
						"GET /api/backoffice/leads/{id}",
						"POST /api/backoffice/leads",
						"PUT /api/backoffice/leads/{id}",
						"DELETE /api/backoffice/leads/{id}",
						"GET /api/backoffice/notifications",
						"PATCH /api/backoffice/notifications/{id}/read",
						"POST /api/backoffice/notifications/read-all",
						"DELETE /api/backoffice/notifications/{id}",
						"GET /api/backoffice/users",
						"GET /api/backoffice/users/{id}",
						"POST /api/backoffice/users",
						"PUT /api/backoffice/users/{id}",
						"PATCH /api/backoffice/users/{id}/active",
						"DELETE /api/backoffice/users/{id}",
						"GET /api/backoffice/me");
	}

	private static boolean hasPreAuthorize(HandlerMethod handler) {
		return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
				|| AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
	}

	/**
	 * The {@code @PreAuthorize} expression Spring Security applies to the handler: the method's if it
	 * has one (it overrides the class's), otherwise the class's; {@code null} when neither has one.
	 */
	private static String effectivePreAuthorize(HandlerMethod handler) {
		PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
		PreAuthorize effective = onMethod != null
				? onMethod
				: AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
		return effective == null ? null : effective.value();
	}

	/** A mapping without methods accepts all of them; Spring MVC serves {@code HEAD} wherever it
	 * serves {@code GET}. Same rule as {@link PublicSurfaceTest}. */
	private static Set<RequestMethod> acceptedMethods(RequestMappingInfo mapping) {
		Set<RequestMethod> declared = mapping.getMethodsCondition().getMethods();
		if (declared.isEmpty()) {
			return EnumSet.allOf(RequestMethod.class);
		}
		EnumSet<RequestMethod> accepted = EnumSet.copyOf(declared);
		if (accepted.contains(RequestMethod.GET)) {
			accepted.add(RequestMethod.HEAD);
		}
		return accepted;
	}
}
