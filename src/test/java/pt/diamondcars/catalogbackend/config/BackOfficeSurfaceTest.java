package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * /api/backoffice/} and (b) carry {@code @PreAuthorize}, on the method or on the class. This is what
 * fails when a back-office task adds a route outside the prefix, or a controller with only the
 * "authenticated" check (a token without a role would pass).
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

	@Test
	void everyNonPublicApiRouteIsUnderTheBackOfficePrefixAndHasPreAuthorize() {
		List<String> outsideThePrefix = new ArrayList<>();
		List<String> withoutPreAuthorize = new ArrayList<>();
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
				}
			}
		}

		assertThat(outsideThePrefix).as("non-public /api routes outside /api/backoffice/").isEmpty();
		assertThat(withoutPreAuthorize).as("non-public /api routes without @PreAuthorize").isEmpty();
		// Positive control: the walk does see this task's six handlers (an empty walk proves nothing).
		assertThat(backOfficeRoutes)
				.contains(
						"GET /api/backoffice/cars",
						"GET /api/backoffice/cars/{id}",
						"POST /api/backoffice/cars",
						"POST /api/backoffice/cars/{id}/reserve",
						"POST /api/backoffice/cars/{id}/release-reservation",
						"PATCH /api/backoffice/cars/{id}/highlight");
	}

	private static boolean hasPreAuthorize(HandlerMethod handler) {
		return AnnotatedElementUtils.hasAnnotation(handler.getMethod(), PreAuthorize.class)
				|| AnnotatedElementUtils.hasAnnotation(handler.getBeanType(), PreAuthorize.class);
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
