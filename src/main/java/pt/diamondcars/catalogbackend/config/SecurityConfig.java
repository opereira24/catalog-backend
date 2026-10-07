package pt.diamondcars.catalogbackend.config;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Security of the whole API (TASK-002): an Auth0 JWT resource server where only {@link
 * PublicEndpoints} is open and everything else needs a valid token. Role rules live in {@code
 * @PreAuthorize} on the controllers (enabled by {@link EnableMethodSecurity}), never here.
 *
 * <p>Decisions that are not obvious from the code, each one measured (see {@code CLAUDE.md} in
 * this package):
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
 *       check is explicit in {@link #validator(String, String)} and a slow Auth0 holds at most a
 *       handful of request threads ({@link Auth0JwkSource}).
 * </ul>
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

	private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

	/** Message of every token rejected because Auth0 is not configured; it ends up in {@code
	 * WWW-Authenticate}, so it must not reveal that the configuration is missing. */
	static final String INVALID_TOKEN_MESSAGE = "Token invalido";

	/** Connect and read timeout of the JWKS fetch from Auth0 (the only call made to Auth0). */
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
		AuthenticationEntryPoint entryPoint = new ApiErrorAuthenticationEntryPoint(jsonMapper);
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
										.authenticationEntryPoint(entryPoint)
										.withObjectPostProcessor(
												new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
													@Override
													public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
														filter.setAuthenticationFailureHandler(
																bearerAuthenticationFailureHandler(entryPoint));
														return filter;
													}
												}));
		return http.build();
	}

	/**
	 * What the bearer token filter does with a token it could not authenticate.
	 *
	 * <ul>
	 *   <li>Invalid token (bad signature, expired, wrong {@code aud}, unknown {@code kid}...): the
	 *       {@code entryPoint}, so 401.
	 *   <li>Token that could not be checked because Auth0's keys are unavailable ({@link
	 *       AuthenticationServiceException}): 500 through {@code sendError}, so Spring Boot's error
	 *       body via the {@code ERROR} dispatch, and one {@code DEBUG} line. Spring's default handler
	 *       rethrows the exception instead, and Tomcat then logs a full stack trace at {@code ERROR}
	 *       for every such request (measured in review r1: 90 MB in 20 s under a flood). The outage
	 *       itself is logged once per failed fetch by {@link Auth0JwkSource}.
	 * </ul>
	 *
	 * @param entryPoint the 401 entry point
	 * @return the failure handler
	 */
	static AuthenticationFailureHandler bearerAuthenticationFailureHandler(AuthenticationEntryPoint entryPoint) {
		return (request, response, exception) -> {
			if (exception instanceof AuthenticationServiceException) {
				log.debug("Token por verificar em {}: {}", request.getRequestURI(), exception.getMessage());
				response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				return;
			}
			entryPoint.commence(request, response, exception);
		};
	}

	/**
	 * The Auth0 {@link JwtDecoder}.
	 *
	 * <ul>
	 *   <li>{@code issuerUri} or {@code audience} blank: a decoder that rejects every token with
	 *       {@link BadJwtException} (so 401), never calls the network, and one {@code WARN} at
	 *       startup. Issuer without audience is rejected too: skipping the {@code aud} check would
	 *       accept any token of the tenant issued for another API.
	 *   <li>Both set: {@link #auth0JwtDecoder(JWKSource, String, String)} over {@link
	 *       Auth0JwkSource#forIssuer(String, Duration)}. Nothing is fetched at startup; the JWKS is
	 *       fetched with {@link #AUTH0_TIMEOUT} connect and read timeouts when the first token needs a
	 *       key, and the source bounds how many requests a slow Auth0 can hold (see its Javadoc).
	 *       If the keys cannot be fetched the request ends in 500 (see {@link
	 *       #bearerAuthenticationFailureHandler(AuthenticationEntryPoint)}), deliberately not 401:
	 *       the token may be valid and a 401 would make the back-office end the user's session over a
	 *       server-side failure.
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
		return auth0JwtDecoder(Auth0JwkSource.forIssuer(issuerUri, AUTH0_TIMEOUT), issuerUri, audience);
	}

	/**
	 * The decoder used with a real Auth0 tenant: RS256 only (what Auth0 signs access tokens with;
	 * {@code none} and HMAC are refused before any key lookup) and {@link #validator(String,
	 * String)}.
	 *
	 * @param jwkSource where the signing keys come from
	 * @param issuerUri the expected {@code iss}
	 * @param audience the audience that must be in {@code aud}
	 * @return the decoder
	 */
	static NimbusJwtDecoder auth0JwtDecoder(JWKSource<SecurityContext> jwkSource, String issuerUri, String audience) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(jwkSource).jwsAlgorithm(SignatureAlgorithm.RS256).build();
		decoder.setJwtValidator(validator(issuerUri, audience));
		return decoder;
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
}
