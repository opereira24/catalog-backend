package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

/**
 * Which {@code AUTH0_ISSUER_URI} values are accepted (TASK-002, review r2, IMPORTANTE 1 and S-b):
 * only the exact form Auth0 puts in {@code iss}, since the {@code iss} check is an exact
 * comparison. Every rejected value gets the hint an operator needs to fix it.
 */
class Auth0IssuerTest {

	@ParameterizedTest(name = "\"{0}\"")
	@ValueSource(
			strings = {
				"https://oteustand.eu.auth0.com/",
				"https://login.oteustand.pt/",
				"https://a.b/",
				"http://127.0.0.1:18555/",
				"http://localhost/",
				"http://localhost:1/",
				"http://[::1]:65535/"
			})
	void issuersInTheFormAuth0EmitsAreAccepted(String issuer) {
		assertThat(Auth0Issuer.problem(issuer)).isEmpty();
		assertThatCode(() -> Auth0JwkSource.jwkSetUrl(issuer)).as("the key source can be built").doesNotThrowAnyException();
	}

	@ParameterizedTest(name = "\"{0}\" -> {1}")
	@MethodSource("malformedIssuers")
	void malformedIssuersAreRejectedWithAHint(String issuer, String hint) {
		assertThat(Auth0Issuer.problem(issuer)).hasValueSatisfying(reason -> assertThat(reason).contains(hint));
	}

	/**
	 * Every malformed value a person could plausibly type into Render, plus absurd ones. Shared with
	 * {@link MalformedAuth0IssuerTest}, which proves none of them stops the application.
	 *
	 * @return issuer and the expected hint
	 */
	static Stream<Arguments> malformedIssuers() {
		String noScheme = "falta o esquema";
		String noSlash = "falta a barra final";
		String whitespace = "espacos";
		String uppercase = "maiusculas";
		String notHttps = "http so e aceite para localhost";
		String otherScheme = "o esquema tem de ser https";
		String onlyDomain = "dominio invalido";
		String badPort = "porta invalida";
		return Stream.of(
				// what the Auth0 dashboard shows as "Domain"
				Arguments.of("oteustand.eu.auth0.com", noScheme),
				Arguments.of("oteustand.eu.auth0.com/", noScheme),
				Arguments.of("https//oteustand.eu.auth0.com/", noScheme),
				Arguments.of("https:/oteustand.eu.auth0.com/", noScheme),
				Arguments.of("//oteustand.eu.auth0.com/", noScheme),
				// Auth0 puts the trailing slash in iss: without it every token was a silent 401
				Arguments.of("https://oteustand.eu.auth0.com", noSlash),
				Arguments.of("http://127.0.0.1:18555", noSlash),
				Arguments.of("http://localhost", noSlash),
				// more than one problem: the hint names one of them, truthfully
				Arguments.of("HTTPS://OTEUSTAND.EU.AUTH0.COM", uppercase),
				Arguments.of("http://oteustand.eu.auth0.com", notHttps),
				Arguments.of("oteustand.eu.auth0.com:443", noScheme),
				// spaces, line breaks, accents, control characters
				Arguments.of(" https://oteustand.eu.auth0.com/", whitespace),
				Arguments.of("https://oteustand.eu.auth0.com/ ", whitespace),
				Arguments.of("https://oteustand.eu.auth0.com/\n", whitespace),
				Arguments.of("https://oteustand.eu.auth0.com/\r\n", whitespace),
				Arguments.of("https://oteustand .eu.auth0.com/", whitespace),
				Arguments.of("https://oteustand.eu.auth0.com/\t", whitespace),
				Arguments.of("https://otéustand.eu.auth0.com/", whitespace),
				Arguments.of("https://oteustand.eu.auth0.com/\u0000", whitespace),
				// the next value has a non-breaking space (U+00A0) on each side, as pasted from a web page
				Arguments.of("\"https://oteustand.eu.auth0.com/\"".replace('"', ' '), whitespace),
				// quotes copied along with the value
				Arguments.of("\"https://oteustand.eu.auth0.com/\"", "tem aspas"),
				Arguments.of("'https://oteustand.eu.auth0.com/'", "tem aspas"),
				Arguments.of("https://oteustand.eu.auth0.com/\"", "tem aspas"),
				// case: the comparison with iss is exact
				Arguments.of("HTTPS://oteustand.eu.auth0.com/", uppercase),
				Arguments.of("https://OTEUSTAND.eu.auth0.com/", uppercase),
				// scheme
				Arguments.of("http://oteustand.eu.auth0.com/", notHttps),
				Arguments.of("ftp://oteustand.eu.auth0.com/", otherScheme),
				Arguments.of("file:///etc/passwd/", otherScheme),
				Arguments.of("wss://oteustand.eu.auth0.com/", otherScheme),
				Arguments.of("https:oteustand.eu.auth0.com/", noScheme),
				// only a scheme, or no domain
				Arguments.of("https://", onlyDomain),
				Arguments.of("https:///", onlyDomain),
				Arguments.of("https://.eu.auth0.com/", onlyDomain),
				Arguments.of("https://localhost/", onlyDomain),
				// ports
				Arguments.of("https://oteustand.eu.auth0.com:443/", onlyDomain),
				Arguments.of("https://oteustand.eu.auth0.com:abc/", onlyDomain),
				Arguments.of("https://oteustand.eu.auth0.com:/", onlyDomain),
				Arguments.of("https://oteustand.eu.auth0.com:8080/", onlyDomain),
				Arguments.of("http://127.0.0.1:0/", badPort),
				Arguments.of("http://127.0.0.1:65536/", badPort),
				Arguments.of("http://127.0.0.1:99999/", badPort),
				Arguments.of("http://localhost:abc/", onlyDomain),
				// path, query, fragment, credentials
				Arguments.of("https://oteustand.eu.auth0.com/oauth/token", onlyDomain),
				Arguments.of("https://oteustand.eu.auth0.com/api/v2/", onlyDomain),
				Arguments.of("https://oteustand.eu.auth0.com//", onlyDomain),
				Arguments.of("https://oteustand.eu.auth0.com/?x=1", onlyDomain),
				Arguments.of("https://oteustand.eu.auth0.com/#frag", onlyDomain),
				Arguments.of("https://user:pass@oteustand.eu.auth0.com/", onlyDomain),
				Arguments.of("https://oteustand.eu.auth0.com/.well-known/jwks.json", onlyDomain),
				// invalid host names
				Arguments.of("https://oteustand.eu.auth0.com./", onlyDomain),
				Arguments.of("https://-oteustand.eu.auth0.com/", onlyDomain),
				Arguments.of("https://oteu_stand.eu.auth0.com/", onlyDomain),
				Arguments.of("https://oteustand..auth0.com/", onlyDomain),
				Arguments.of("https://oteustand.eu.auth0.com%2f/", onlyDomain),
				Arguments.of("https://oteustand.eu.auth0.com%2F/", uppercase),
				Arguments.of("https://[oteustand]/", onlyDomain),
				Arguments.of("https://{oteustand}.eu.auth0.com/", onlyDomain),
				// absurdly long: a 64-character label, a 254-character domain (one over the DNS limit,
				// see longestValidDomainIsAccepted), 100 000 characters
				Arguments.of("https://" + "a".repeat(64) + ".auth0.com/", onlyDomain),
				Arguments.of("https://" + ("a".repeat(63) + ".").repeat(3) + "a".repeat(62) + "/", onlyDomain),
				Arguments.of("https://" + "a.".repeat(50_000) + "com/", onlyDomain),
				Arguments.of("x".repeat(100_000), noScheme));
	}

	@Test
	void longestValidDomainIsAccepted() {
		String domain = ("a".repeat(63) + ".").repeat(3) + "a".repeat(61);
		assertThat(domain).hasSize(253);
		assertThat(Auth0Issuer.problem("https://" + domain + "/")).isEmpty();
	}

	@Test
	void loggedValueIsQuotedPrintableAndShort() {
		assertThat(Auth0Issuer.forLog(" https://x.y/\n")).isEqualTo("\" https://x.y/?\"");
		assertThat(Auth0Issuer.forLog("https://otéustand/")).isEqualTo("\"https://ot?ustand/\"");
		assertThat(Auth0Issuer.forLog("x".repeat(100_000))).isEqualTo("\"" + "x".repeat(100) + "\"... (100000 caracteres)");
	}
}
