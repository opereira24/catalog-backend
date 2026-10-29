package pt.diamondcars.catalogbackend.config;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checks {@code AUTH0_ISSUER_URI} before anything is built from it (TASK-002, review r2,
 * IMPORTANTE 1). A malformed value used to make the {@code jwtDecoder} bean throw, so the whole
 * application failed to start, public site included; now the decoder rejects every token instead
 * (see {@code SecurityConfig#jwtDecoder}) and the reason is logged once.
 *
 * <p>The accepted form is exactly what Auth0 puts in the {@code iss} claim, {@code
 * https://<domain>/}: lowercase, with the trailing slash, without port, path, query or fragment.
 * The {@code iss} check is an exact string comparison, so any other spelling of the same tenant
 * (no trailing slash, uppercase, explicit {@code :443}) would reject every real token without any
 * error at startup (review r2, S-b). Rejecting it here costs nothing (those tokens were rejected
 * anyway), avoids fetching keys that can never be used, and says why. The value is never
 * normalized: what is compared with {@code iss} is what was configured.
 *
 * <p>{@code http} is accepted only for a loopback host (an Auth0 tenant running in the test JVM,
 * {@code config/support/FakeAuth0}); a real tenant is always {@code https}, and its keys must never
 * travel in clear text.
 */
final class Auth0Issuer {

	/** The production value, used in the startup message. */
	static final String EXAMPLE = "https://oteustand.eu.auth0.com/";

	/** One DNS label: letters, digits and inner hyphens, at most 63 characters, lowercase. */
	private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";

	/** {@code https://<domain>/}, the domain with at least two labels and at most 253 characters. */
	private static final Pattern TENANT = Pattern.compile("https://(?=[^/]{1,253}/\\z)" + LABEL + "(?:\\." + LABEL + ")+/");

	/** {@code http://<loopback>[:port]/}, for a tenant in the test JVM. */
	private static final Pattern LOOPBACK = Pattern.compile("http://(?:localhost|127\\.0\\.0\\.1|\\[::1\\])(?::[0-9]{1,5})?/");

	/** Start of an {@code http} issuer on a loopback host, valid or not (for the hint). */
	private static final Pattern LOOPBACK_PREFIX = Pattern.compile("http://(?:localhost|127\\.0\\.0\\.1|\\[::1\\])(?:[:/]|\\z)");

	/** How much of a rejected value goes into the log. */
	private static final int LOGGED_LENGTH = 100;

	private Auth0Issuer() {}

	/**
	 * Why {@code issuerUri} cannot be used as the Auth0 issuer, if it cannot.
	 *
	 * @param issuerUri a non-blank {@code AUTH0_ISSUER_URI}
	 * @return empty when it is valid, otherwise a short reason (in Portuguese, it goes to the log)
	 */
	static Optional<String> problem(String issuerUri) {
		return isValid(issuerUri) ? Optional.empty() : Optional.of(reason(issuerUri));
	}

	/**
	 * {@code issuerUri} as it can safely go into one log line: characters outside printable ASCII
	 * shown as {@code ?}, quoted (so leading or trailing spaces are visible), cut at {@value
	 * #LOGGED_LENGTH} characters.
	 *
	 * @param issuerUri the configured value
	 * @return the printable form
	 */
	static String forLog(String issuerUri) {
		StringBuilder printable = new StringBuilder("\"");
		issuerUri.chars().limit(LOGGED_LENGTH).forEach(c -> printable.append(c >= 0x20 && c <= 0x7e ? (char) c : '?'));
		printable.append('"');
		if (issuerUri.length() > LOGGED_LENGTH) {
			printable.append("... (").append(issuerUri.length()).append(" caracteres)");
		}
		return printable.toString();
	}

	private static boolean isValid(String issuerUri) {
		if (TENANT.matcher(issuerUri).matches()) {
			return true;
		}
		if (!LOOPBACK.matcher(issuerUri).matches()) {
			return false;
		}
		int port = URI.create(issuerUri).getPort();
		return port == -1 || (port >= 1 && port <= 65_535);
	}

	/**
	 * The first of these checks that fails, so every hint is a true statement about the value even
	 * when it has more than one problem; the log line always adds the expected form anyway.
	 */
	private static String reason(String issuerUri) {
		if (issuerUri.chars().anyMatch(c -> c <= 0x20 || c > 0x7e)) {
			return "tem espacos, acentos ou caracteres de controlo";
		}
		if (issuerUri.matches("[\"'].*|.*[\"']")) {
			return "tem aspas, e o valor vai sem elas";
		}
		int schemeEnd = issuerUri.indexOf("://");
		if (schemeEnd < 0) {
			return "falta o esquema https://";
		}
		if (!issuerUri.equals(issuerUri.toLowerCase(Locale.ROOT))) {
			return "tem maiusculas, e o iss do Auth0 e em minusculas";
		}
		String scheme = issuerUri.substring(0, schemeEnd);
		boolean loopback = LOOPBACK_PREFIX.matcher(issuerUri).lookingAt();
		if (scheme.equals("http") && !loopback) {
			return "http so e aceite para localhost, o Auth0 e sempre https";
		}
		if (!scheme.equals("https") && !scheme.equals("http")) {
			return "o esquema tem de ser https";
		}
		if (isValid(issuerUri + "/")) {
			return "falta a barra final, e o Auth0 emite o iss com ela";
		}
		if (LOOPBACK.matcher(issuerUri).matches()) {
			return "porta invalida";
		}
		return "dominio invalido, ou com porta, caminho, query, fragmento ou credenciais";
	}
}
