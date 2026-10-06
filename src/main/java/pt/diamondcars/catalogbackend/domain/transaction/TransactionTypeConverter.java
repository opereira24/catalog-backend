package pt.diamondcars.catalogbackend.domain.transaction;

import jakarta.persistence.Converter;
import pt.diamondcars.catalogbackend.domain.support.AbstractPersistentEnumConverter;

/**
 * Persists {@link TransactionType} to/from the {@code transactions.tipo} column.
 */
@Converter(autoApply = true)
public class TransactionTypeConverter extends AbstractPersistentEnumConverter<TransactionType> {

	/** Creates the converter bound to {@link TransactionType}. */
	public TransactionTypeConverter() {
		super(TransactionType.class);
	}
}
