package pt.diamondcars.catalogbackend.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import pt.diamondcars.catalogbackend.web.dto.validation.StorableDate;

/**
 * Request body of {@code POST /api/backoffice/leads} and {@code PUT /api/backoffice/leads/{id}}
 * (TASK-006, AC E.2), ported from {@code dcbo-backend}'s {@code LeadRequest} (the rules of {@code
 * leadValidationSchema} in {@code dcbo/src/utils/validation.js}, plus what {@code
 * dcbo/src/components/lead-form.js} sends alongside).
 *
 * <p>Not applied identically by both endpoints: {@code PUT} writes exactly {@link #nome}, {@link
 * #telefone}, {@link #email}, {@link #notas}, {@link #status} and {@link #followUpDate} (the 6 fields
 * {@code updateLead} of {@code dcbo} always sends) and ignores the car fields; {@code POST} also takes
 * the car and its snapshot. Keys this record does not declare are ignored (Boot's default): {@code
 * origem}, {@code mensagem} and {@code id} in a body have no effect; {@code origem} is always {@code
 * backoffice} on {@code POST}.
 *
 * <p>No HTML sanitizing on the server, as in the rest of the back-office: {@code dcbo} (React)
 * escapes when it renders.
 *
 * <p>Differences from {@code dcbo-backend}: {@link StorableDate} on {@link #followUpDate} ({@code
 * -5000-01-01} reached the {@code DATE} column and came back as a 409) and {@code @Digits} on {@link
 * #carroPreco} ({@code NUMERIC(12,2)} rounds silently, see {@code web/CLAUDE.md}).
 *
 * @param nome required, 2 to 100 characters
 * @param telefone required, Portuguese phone ({@link ClientRequest#PHONE_REGEXP})
 * @param email optional, a valid address, at most 255 characters
 * @param notas optional notes of the stand's staff, at most 1000 characters
 * @param status required, one of the 8 values of {@code leads_status_check}
 * @param followUpDate optional date of the next follow-up, within {@code [0001-01-01, 9999-12-31]};
 *     {@code null} or absent clears it on {@code PUT}
 * @param carroId the car the lead is about, optional, only on {@code POST}; unknown is a 404
 * @param carroMarca brand snapshot, at most 100 characters, only on {@code POST}
 * @param carroModelo model snapshot, at most 100 characters, only on {@code POST}
 * @param carroPreco price snapshot, from 0 to {@value CarRequest#PRICE_MAX_VALUE}, at most 2 decimal
 *     places, only on {@code POST}
 */
public record BackOfficeLeadRequest(
		@NotBlank @Size(min = 2, max = 100) String nome,
		@NotBlank @Pattern(regexp = ClientRequest.PHONE_REGEXP) String telefone,
		@Email @Size(max = 255) String email,
		@Size(max = 1000) String notas,
		@NotBlank @Pattern(regexp = BackOfficeLeadRequest.STATUS_REGEXP) String status,
		@StorableDate LocalDate followUpDate,
		UUID carroId,
		@Size(max = 100) String carroMarca,
		@Size(max = 100) String carroModelo,
		@DecimalMin("0") @DecimalMax(CarRequest.PRICE_MAX_VALUE) @Digits(integer = 8, fraction = 2)
		BigDecimal carroPreco) {

	/** The 8 values of {@code leads_status_check} (V2), the stored lower-case ones only. */
	static final String STATUS_REGEXP =
			"^(ativo|contactado|test_drive_marcado|test_drive_realizado|proposta_feita|negociacao|vendido|desistiu)$";
}
