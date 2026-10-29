package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * Guard of the public surface (TASK-002, AC E.7): walks every controller mapping of the
 * application, every method it accepts ({@code HEAD} included wherever {@code GET} is), and
 * collects the ones {@link PublicEndpoints#MATCHER} lets through without a token. The result must
 * be exactly the public site's endpoints.
 *
 * <p>This is what fails when a back-office task adds, for example, {@code GET /api/cars/stats}:
 * it would silently be public through {@code /api/cars/{id}}. Same context as the public
 * controller tests (no extra Spring context).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PublicSurfaceTest extends AbstractPostgresIntegrationTest {

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	void onlyThePublicSiteEndpointsAreReachableWithoutAToken() {
		List<String> publicRequests = new ArrayList<>();
		for (RequestMappingInfo mapping : handlerMapping.getHandlerMethods().keySet()) {
			for (PathPattern pattern : mapping.getPathPatternsCondition().getPatterns()) {
				String path = pattern.getPatternString().replaceAll("\\{[^}]+}", "x");
				for (RequestMethod method : acceptedMethods(mapping)) {
					MockHttpServletRequest request = new MockHttpServletRequest(method.name(), path);
					if (PublicEndpoints.MATCHER.matches(request)) {
						publicRequests.add(method + " " + pattern.getPatternString());
					}
				}
			}
		}

		assertThat(publicRequests)
				.containsExactlyInAnyOrder(
						"GET /api/cars",
						"HEAD /api/cars",
						"GET /api/cars/{id}",
						"HEAD /api/cars/{id}",
						"GET /api/cars/highlights",
						"HEAD /api/cars/highlights",
						"POST /api/leads");
	}

	/** A mapping without methods accepts all of them; Spring MVC serves {@code HEAD} wherever it
	 * serves {@code GET}. */
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
