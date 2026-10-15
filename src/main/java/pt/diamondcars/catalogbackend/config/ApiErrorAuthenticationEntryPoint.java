package pt.diamondcars.catalogbackend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import pt.diamondcars.catalogbackend.web.dto.ApiError;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes every 401 of the API (no token, or a token that fails validation) as an {@link ApiError},
 * the same body {@code ApiExceptionHandler} produces for 400, 403, 404 and 409 (TASK-002, AC
 * C.6), so the back-office parses a single error format.
 *
 * <p>{@link BearerTokenAuthenticationEntryPoint} runs first and keeps the RFC 6750 behaviour: the
 * 401 status and the {@code WWW-Authenticate: Bearer ...} header, where the token problem
 * (expired, wrong audience, ...) is described. The body message is always the same on purpose: the
 * token detail belongs in that header, not in a body a browser might show to the user.
 *
 * <p>The body is serialized with the application's {@link JsonMapper} (the one Spring MVC uses for
 * {@code ApiExceptionHandler}), so {@code timestamp} has the same format in every error.
 */
final class ApiErrorAuthenticationEntryPoint implements AuthenticationEntryPoint {

	static final String MESSAGE = "Autenticacao necessaria";

	private final BearerTokenAuthenticationEntryPoint bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
	private final JsonMapper jsonMapper;

	/**
	 * Creates the entry point.
	 *
	 * @param jsonMapper the application's JSON mapper
	 */
	ApiErrorAuthenticationEntryPoint(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	/**
	 * Sets the 401 status and the {@code WWW-Authenticate} header, then writes the {@link ApiError}
	 * body.
	 *
	 * @param request the rejected request
	 * @param response the response to write
	 * @param authException why authentication failed (only reflected in {@code WWW-Authenticate})
	 * @throws IOException if the body cannot be written
	 */
	@Override
	public void commence(
			HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
			throws IOException {
		bearerEntryPoint.commence(request, response, authException);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		jsonMapper.writeValue(
				response.getOutputStream(), ApiError.of(HttpStatus.UNAUTHORIZED, MESSAGE, request.getRequestURI()));
	}
}
