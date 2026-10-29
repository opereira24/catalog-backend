package pt.diamondcars.catalogbackend.domain.user;

import java.util.Arrays;
import pt.diamondcars.catalogbackend.domain.support.PersistentEnum;

/**
 * Role of an {@link AppUser} inside the back-office, matching the two values of {@code
 * USER_ROLES} in {@code dcbo/src/services/userManagementService.js:124} ({@code admin}, {@code
 * user}).
 */
public enum AppUserRole implements PersistentEnum {

	ADMIN("admin"),
	USER("user");

	private final String value;

	AppUserRole(String value) {
		this.value = value;
	}

	@Override
	public String getValue() {
		return value;
	}

	/**
	 * Resolves the constant whose {@link #getValue()} equals the given raw string, the inverse of
	 * {@link #getValue()} — used to map an incoming DTO's plain string field onto this enum,
	 * mirroring {@code
	 * pt.diamondcars.catalogbackend.domain.lead.LeadStatus#fromValue(String)}.
	 *
	 * @param value the raw database/JSON value to resolve, e.g. {@code "admin"}
	 * @return the matching constant
	 * @throws IllegalArgumentException if no constant has this value
	 */
	public static AppUserRole fromValue(String value) {
		return Arrays.stream(values())
				.filter(candidate -> candidate.getValue().equals(value))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown app user role: " + value));
	}
}
