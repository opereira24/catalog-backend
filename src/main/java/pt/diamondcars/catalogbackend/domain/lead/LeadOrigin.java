package pt.diamondcars.catalogbackend.domain.lead;

import java.util.Arrays;
import pt.diamondcars.catalogbackend.domain.support.PersistentEnum;

/**
 * Origin of a {@link Lead}, matching the two values the public site ({@code dc}) ever submits and
 * the {@code CHECK} constraint of {@code leads.origem} in {@code V1__init.sql}: {@link #WEBSITE}
 * (car detail page interest form) and {@link #WEBSITE_CONTACTO} (general contact form, no
 * specific car).
 */
public enum LeadOrigin implements PersistentEnum {

	WEBSITE("website"),
	WEBSITE_CONTACTO("website-contacto");

	private final String value;

	LeadOrigin(String value) {
		this.value = value;
	}

	@Override
	public String getValue() {
		return value;
	}

	/**
	 * Resolves the constant whose {@link #getValue()} equals the given raw string, the inverse of
	 * {@link #getValue()} — used to map an incoming DTO's plain string field onto this enum.
	 *
	 * @param value the raw database/JSON value to resolve, e.g. {@code "website-contacto"}
	 * @return the matching constant
	 * @throws IllegalArgumentException if no constant has this value
	 */
	public static LeadOrigin fromValue(String value) {
		return Arrays.stream(values())
				.filter(candidate -> candidate.getValue().equals(value))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown lead origin: " + value));
	}
}
