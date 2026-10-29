package pt.diamondcars.catalogbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * {@code .env.example} names every environment variable {@code application.yml} reads, and nothing
 * else (TASK-008, A.4). It is the list the deploy on Render is configured from: a variable missing
 * there is a setting nobody knows exists, one too many is a setting that does nothing. No Spring, no
 * database: it reads the two files from the module root (the working directory of Surefire).
 *
 * <p>Mutant this class kills: deleting the {@code DB_PASSWORD=} line from {@code .env.example} (the
 * failure message names the missing variable).
 */
class EnvExampleTest {

	/** {@code ${NAME}} or {@code ${NAME:default}}: environment variables are upper case by convention here. */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*)[:}]");

	/** {@code NAME=value} or, for an optional variable left at its default, {@code # NAME=value}. */
	private static final Pattern ENTRY = Pattern.compile("^(?:#\\s*)?([A-Z][A-Z0-9_]*)=", Pattern.MULTILINE);

	@Test
	void envExampleListsExactlyTheVariablesApplicationYmlReads() throws IOException {
		Set<String> read = names(PLACEHOLDER, Path.of("src/main/resources/application.yml"));
		Set<String> documented = names(ENTRY, Path.of(".env.example"));

		assertThat(read).as("variables read by application.yml").contains("DB_URL", "AUTH0_AUDIENCE", "PORT");
		assertThat(documented)
				.as("variables in .env.example (missing ones are read by application.yml but not documented)")
				.containsExactlyInAnyOrderElementsOf(read);
	}

	private static Set<String> names(Pattern pattern, Path file) throws IOException {
		Matcher matcher = pattern.matcher(Files.readString(file));
		Set<String> names = new TreeSet<>();
		while (matcher.find()) {
			names.add(matcher.group(1));
		}
		return names;
	}
}
