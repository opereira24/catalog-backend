package pt.diamondcars.catalogbackend.config;

import jakarta.servlet.DispatcherType;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security of the whole API (TASK-002): an Auth0 JWT resource server where only {@link
 * PublicEndpoints} is open and everything else needs a valid token. Role rules live in {@code
 * @PreAuthorize} on the controllers (enabled by {@link EnableMethodSecurity}), never here.
 *
 * <p>Decisions that are not obvious from the code, each one measured (see {@code CLAUDE.md},
 * "Seguranca"):
 *
 * <ul>
 *   <li>The {@code ERROR} dispatch is permitted. Spring Security authorizes every dispatch type,
 *       and errors that Spring MVC or Tomcat produce with {@code sendError} (406 on {@code POST
 *       /api/leads} with {@code Accept: application/xml}, the 400 of the firewall) are rendered by
 *       a second, {@code ERROR} dispatch to {@code /error}; without this rule the public site would
 *       get 401 instead of those statuses. A direct {@code GET /error} is a {@code REQUEST}
 *       dispatch and stays protected.
 *   <li>On the public endpoints the {@code Authorization} header is ignored ({@link
 *       #bearerTokenResolver()}): the site never depends on Auth0 being up, and a back-office user
 *       with an expired token sees the public pages exactly like a visitor.
 *   <li>The {@link JwtDecoder} is declared here, not auto-configured: with {@code AUTH0_*} missing
 *       it rejects every token without any network call, and with {@code AUTH0_*} set the audience
 *       check is explicit in {@link #validator(String, String)}.
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	/** Message of every token rejected because Auth0 is not configured; it ends up in {@code
	 * WWW-Authenticate}, so it must not reveal that the configuration is missing. */
	static final String INVALID_TOKEN_MESSAGE = "Token invalido";

	/** Connect and read timeout of each call to Auth0 (OIDC discovery, JWKS). */
	static final Duration AUTH0_TIMEOUT = Duration.ofSeconds(3);

	/**
	 * The single filter chain of the application: stateless, no CSRF (bearer tokens only, no
	 * cookies), CORS from {@link CorsConfig}, {@code ERROR} dispatch and {@link PublicEndpoints}
	 * open, everything else authenticated by JWT.
	 *
	 * @param http the {@link HttpSecurity} builder
	 * @param auth0RolesConverter turns the Auth0 roles claim into {@code ROLE_*} authorities
	 * @param corsConfigurationSource the only CORS policy of the application
	 * @param jsonMapper the application's JSON mapper, used for the 401 body
	 * @return the filter chain
	 */
	@Bean
	public SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			Auth0RolesConverter auth0RolesConverter,
			CorsConfigurationSource corsConfigurationSource,
			JsonMapper jsonMapper) {
		JwtAuthenticationConverter jwtAuthenticationConverter = new JwtAuthenticationConverter();
		jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(auth0RolesConverter);

		http.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.cors(cors -> cors.configurationSource(corsConfigurationSource))
				.authorizeHttpRequests(
						authorize ->
								authorize
										.dispatcherTypeMatchers(DispatcherType.ERROR)
										.permitAll()
										.requestMatchers(PublicEndpoints.MATCHER)
										.permitAll()
										.anyRequest()
										.authenticated())
				.oauth2ResourceServer(
						oauth2 ->
								oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
										.bearerTokenResolver(bearerTokenResolver())
										.authenticationEntryPoint(new ApiErrorAuthenticationEntryPoint(jsonMapper)));
		return http.build();
	}

	/**
	 * The Auth0 {@link JwtDecoder}.
	 *
	 * <ul>
	 *   <li>{@code issuerUri} or {@code audience} blank: a decoder that rejects every token with
	 *       {@link BadJwtException} (so 401), never calls the network, and one {@code WARN} at
	 *       startup. Issuer without audience is rejected too: skipping the {@code aud} check would
	 *       accept any token of the tenant issued for another API.
	 *   <li>Both set: resolved lazily ({@link SupplierJwtDecoder}, no network at startup), OIDC
	 *       discovery and JWKS through a {@link RestTemplate} with {@link #AUTH0_TIMEOUT} timeouts
	 *       (the default has none, and a slow Auth0 would hold Tomcat threads), validated by {@link
	 *       #validator(String, String)}. If Auth0 is unreachable the discovery fails with {@code
	 *       JwtDecoderInitializationException}, which is not an authentication error: the request
	 *       ends in 500, deliberately not 401, because the token may be valid and a 401 would make
	 *       the back-office end the user's session over a server-side failure. It is retried on the
	 *       next request.
	 * </ul>
	 *
	 * @param issuerUri {@code app.auth0.issuer-uri} ({@code AUTH0_ISSUER_URI}), may be blank
	 * @param audience {@code app.auth0.audience} ({@code AUTH0_AUDIENCE}), may be blank
	 * @return the decoder
	 */
	@Bean
	public JwtDecoder jwtDecoder(
			@Value("${app.auth0.issuer-uri:}") String issuerUri, @Value("${app.auth0.audience:}") String audience) {
		if (!StringUtils.hasText(issuerUri) || !StringUtils.hasText(audience)) {
			log.warn(
					"AUTH0_ISSUER_URI e/ou AUTH0_AUDIENCE em falta: todos os tokens sao recusados e todos os "
							+ "endpoints protegidos respondem 401 (os publicos nao sao afetados)");
			return token -> {
				throw new BadJwtException(INVALID_TOKEN_MESSAGE);
			};
		}
		RestTemplate restTemplate = auth0RestTemplate();
		return new SupplierJwtDecoder(
				() -> {
					NimbusJwtDecoder decoder =
							NimbusJwtDecoder.withIssuerLocation(issuerUri).restOperations(restTemplate).build();
					decoder.setJwtValidator(validator(issuerUri, audience));
					return decoder;
				});
	}

	/**
	 * Validation applied to every decoded token: expiry and not-before (with the default clock
	 * skew), {@code iss} equal to {@code issuerUri}, and an {@code aud} list that contains {@code
	 * audience}.
	 *
	 * @param issuerUri the expected issuer
	 * @param audience the audience that must be present in {@code aud}
	 * @return the composed validator
	 */
	static OAuth2TokenValidator<Jwt> validator(String issuerUri, String audience) {
		OAuth2TokenValidator<Jwt> audienceValidator =
				new JwtClaimValidator<List<String>>(
						JwtClaimNames.AUD, audiences -> audiences != null && audiences.contains(audience));
		return new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefaultWithIssuer(issuerUri), audienceValidator);
	}

	/**
	 * Ignores the token on {@link PublicEndpoints} (returns {@code null}, so the request is treated
	 * as anonymous and the decoder is never called) and reads it the standard way everywhere else.
	 * Deliberately not a bean: the DSL would otherwise also pick it up implicitly, and removing the
	 * explicit wiring would not be visible in the chain.
	 */
	private static BearerTokenResolver bearerTokenResolver() {
		DefaultBearerTokenResolver standard = new DefaultBearerTokenResolver();
		return request -> PublicEndpoints.MATCHER.matches(request) ? null : standard.resolve(request);
	}

	private static RestTemplate auth0RestTemplate() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(AUTH0_TIMEOUT);
		requestFactory.setReadTimeout(AUTH0_TIMEOUT);
		return new RestTemplate(requestFactory);
	}
}
