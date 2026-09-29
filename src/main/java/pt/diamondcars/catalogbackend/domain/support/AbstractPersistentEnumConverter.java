package pt.diamondcars.catalogbackend.domain.support;

import jakarta.persistence.AttributeConverter;
import java.util.EnumSet;

/**
 * Reusable {@link AttributeConverter} base for every {@link PersistentEnum} in the domain model,
 * converting to/from the exact string each constant declares via {@link
 * PersistentEnum#getValue()}.
 *
 * @param <E> the enum type being converted, which must implement {@link PersistentEnum}
 */
public abstract class AbstractPersistentEnumConverter<E extends Enum<E> & PersistentEnum>
		implements AttributeConverter<E, String> {

	private final Class<E> enumType;

	/**
	 * Creates a converter for the given enum type.
	 *
	 * @param enumType the concrete enum class this converter handles
	 */
	protected AbstractPersistentEnumConverter(Class<E> enumType) {
		this.enumType = enumType;
	}

	/**
	 * Converts an enum constant to the string stored in the database column.
	 *
	 * @param attribute the enum constant to convert, or {@code null}
	 * @return {@link PersistentEnum#getValue()} of {@code attribute}, or {@code null} if {@code
	 *     attribute} is {@code null}
	 */
	@Override
	public String convertToDatabaseColumn(E attribute) {
		return attribute == null ? null : attribute.getValue();
	}

	/**
	 * Converts a database column value back to its enum constant.
	 *
	 * @param dbData the raw string stored in the database, or {@code null}
	 * @return the enum constant whose {@link PersistentEnum#getValue()} equals {@code dbData}, or
	 *     {@code null} if {@code dbData} is {@code null}
	 * @throws IllegalArgumentException if {@code dbData} does not match any known constant of the
	 *     enum type — signals a value written outside this converter that the current enum does
	 *     not yet account for
	 */
	@Override
	public E convertToEntityAttribute(String dbData) {
		if (dbData == null) {
			return null;
		}
		return EnumSet.allOf(enumType).stream()
				.filter(candidate -> candidate.getValue().equals(dbData))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException(
						"Unknown persisted value '" + dbData + "' for enum " + enumType.getSimpleName()));
	}
}
