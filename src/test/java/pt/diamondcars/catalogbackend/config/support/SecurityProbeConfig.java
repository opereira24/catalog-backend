package pt.diamondcars.catalogbackend.config.support;

import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registers {@link SecurityProbeController}, a protected endpoint that exists only in tests: no
 * back-office endpoint exists yet in {@code src/main} (they arrive from TASK-003 on), and the
 * security chain must be exercised against one.
 *
 * <p>How it gets registered matters. Component scanning skips a {@code @TestConfiguration} and its
 * nested classes, so no context sees the probe by accident. When a test {@code @Import}s this
 * class, Spring processes its member classes and registers the nested {@code @RestController}
 * itself; declaring it again as a {@code @Bean} maps the same routes twice and the context fails
 * with "Ambiguous mapping" (measured).
 */
@TestConfiguration(proxyBeanMethods = false)
public class SecurityProbeConfig {

	/** Protected route that returns the authorities of the current authentication. */
	public static final String PROBE_PATH = "/api/security-probe";

	/** Protected route that also requires {@code ROLE_ADMIN} through {@code @PreAuthorize}. */
	public static final String ADMIN_PROBE_PATH = "/api/security-probe/admin";

	/** Test-only controller behind {@link #PROBE_PATH} and {@link #ADMIN_PROBE_PATH}. */
	@RestController
	public static class SecurityProbeController {

		/**
		 * @return the authorities of the current authentication, as plain strings
		 */
		@GetMapping(PROBE_PATH)
		public List<String> authorities() {
			return currentAuthorities();
		}

		/**
		 * @return the authorities of the current authentication, only for {@code ROLE_ADMIN}
		 */
		@GetMapping(ADMIN_PROBE_PATH)
		@PreAuthorize("hasRole('ADMIN')")
		public List<String> adminOnly() {
			return currentAuthorities();
		}

		private static List<String> currentAuthorities() {
			return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
					.map(GrantedAuthority::getAuthority)
					.toList();
		}
	}
}
