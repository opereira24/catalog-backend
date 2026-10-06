package pt.diamondcars.catalogbackend.domain.lead;

import java.util.Arrays;
import pt.diamondcars.catalogbackend.domain.support.PersistentEnum;

/**
 * Status of a {@link Lead} in the back-office pipeline, mirroring the {@code leads_status_check}
 * constraint of {@code V2__unified_back_office_schema.sql} — itself derived from the options in
 * {@code dcbo/src/components/lead-form.js} plus {@code 'ativo'}, the status the public site
 * writes ({@code dc/src/services/firebaseService.js:103}). Ported from {@code dcbo-backend}.
 */
public enum LeadStatus implements PersistentEnum {

	ATIVO("ativo"),
	CONTACTADO("contactado"),
	TEST_DRIVE_MARCADO("test_drive_marcado"),
	TEST_DRIVE_REALIZADO("test_drive_realizado"),
	PROPOSTA_FEITA("proposta_feita"),
	NEGOCIACAO("negociacao"),
	VENDIDO("vendido"),
	DESISTIU("desistiu");

	private final String value;

	LeadStatus(String value) {
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
	 * @param value the raw database/JSON value to resolve, e.g. {@code "test_drive_marcado"}
	 * @return the matching constant
	 * @throws IllegalArgumentException if no constant has this value
	 */
	public static LeadStatus fromValue(String value) {
		return Arrays.stream(values())
				.filter(candidate -> candidate.getValue().equals(value))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Unknown lead status: " + value));
	}
}
