package pt.diamondcars.catalogbackend.domain.support;

/**
 * Contract implemented by every domain enum that is persisted as its own explicit string value
 * (via {@link AbstractPersistentEnumConverter}) rather than through JPA's {@code @Enumerated}.
 *
 * <p>Plain {@code @Enumerated} in string mode was deliberately not used: it persists {@link
 * Enum#name()} verbatim (upper snake_case, e.g. {@code "WEBSITE_CONTACTO"}), which would violate
 * the lower-kebab-case string values the {@code V1__init.sql} {@code leads.origem} {@code CHECK}
 * constraint relies on ({@code 'website-contacto'} contains a hyphen that cannot even be a valid
 * Java enum constant name). This converter-based approach keeps full compile-time type safety in
 * Java, is never ordinal-based, and never fights the existing schema — the same pattern already
 * used by {@code dcbo-backend} for its own persistent enums.
 */
public interface PersistentEnum {

	/**
	 * Returns the exact string stored in the database column for this enum constant.
	 *
	 * @return the database representation of this constant
	 */
	String getValue();
}
