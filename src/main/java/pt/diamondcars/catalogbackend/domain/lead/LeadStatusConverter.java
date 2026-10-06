package pt.diamondcars.catalogbackend.domain.lead;

import jakarta.persistence.Converter;
import pt.diamondcars.catalogbackend.domain.support.AbstractPersistentEnumConverter;

/**
 * Persists {@link LeadStatus} to/from the {@code leads.status} column.
 */
@Converter(autoApply = true)
public class LeadStatusConverter extends AbstractPersistentEnumConverter<LeadStatus> {

	/** Creates the converter bound to {@link LeadStatus}. */
	public LeadStatusConverter() {
		super(LeadStatus.class);
	}
}
