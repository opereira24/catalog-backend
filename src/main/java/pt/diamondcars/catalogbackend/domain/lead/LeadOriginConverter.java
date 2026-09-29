package pt.diamondcars.catalogbackend.domain.lead;

import jakarta.persistence.Converter;
import pt.diamondcars.catalogbackend.domain.support.AbstractPersistentEnumConverter;

/**
 * Persists {@link LeadOrigin} to/from the {@code leads.origem} column.
 */
@Converter(autoApply = true)
public class LeadOriginConverter extends AbstractPersistentEnumConverter<LeadOrigin> {

	/** Creates the converter bound to {@link LeadOrigin}. */
	public LeadOriginConverter() {
		super(LeadOrigin.class);
	}
}
