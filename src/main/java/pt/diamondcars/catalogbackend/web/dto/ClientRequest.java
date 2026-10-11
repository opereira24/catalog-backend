package pt.diamondcars.catalogbackend.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request payload for {@code POST}/{@code PUT /api/backoffice/clients/{id}} (TASK-004), ported
 * unchanged from {@code dcbo-backend}'s {@code ClientRequest}, which replicates the validation
 * rules of {@code dcbo/src/utils/validation.js:405-418} ({@code clientValidationSchema}).
 *
 * <p>Never exposes or accepts an {@code id}: the entity identifier always comes from the URL path
 * (mirrors {@link CarRequest}). Never accepts {@code purchasesCount}: that counter is derived
 * server-side from sales, never written by the caller.
 *
 * @param name full name, required, 2 to 100 characters ({@code validateName})
 * @param email optional, but must be a valid address when present ({@code validateEmail}; {@link
 *     Email} already treats {@code null}/blank as valid, matching the browser rule)
 * @param phone required, Portuguese mobile/landline format (optional {@code +351 } prefix, then a
 *     digit starting with 2 or 9, then 8 more digits — {@code PATTERNS.PHONE_PT})
 * @param nif optional, exactly 9 digits when present ({@code validateNIF})
 * @param address optional, free text, max 255 chars ({@code LIMITS.TEXT_MEDIUM})
 * @param postalCode optional, Portuguese format {@code NNNN-NNN} when present ({@code
 *     validatePostalCode})
 * @param notes optional, free text, max 1000 chars ({@code LIMITS.TEXT_LONG})
 */
public record ClientRequest(
		@NotBlank @Size(min = 2, max = 100) String name,
		@Email @Size(max = 255) String email,
		@NotBlank @Pattern(regexp = ClientRequest.PHONE_REGEXP) String phone,
		@Pattern(regexp = ClientRequest.NIF_REGEXP) String nif,
		@Size(max = 255) String address,
		@Pattern(regexp = ClientRequest.POSTAL_CODE_REGEXP) String postalCode,
		@Size(max = 1000) String notes) {

	/**
	 * Equivalent to {@code PATTERNS.PHONE_PT} ({@code dcbo/src/utils/validation.js:22}): an
	 * optional {@code +351 } prefix, then a number starting with 2 or 9 followed by 8 more digits.
	 */
	static final String PHONE_REGEXP = "^(\\+351\\s?)?[29]\\d{8}$";

	/**
	 * Equivalent to {@code PATTERNS.NIF_PT} ({@code dcbo/src/utils/validation.js:23}), widened to
	 * also accept an empty string: {@code validateNIF} treats a missing NIF as valid (the field is
	 * optional), and {@link Pattern} alone does not run against an empty {@link String} the way it
	 * skips {@code null}.
	 */
	static final String NIF_REGEXP = "^$|^\\d{9}$";

	/**
	 * Equivalent to {@code PATTERNS.POSTAL_CODE_PT} ({@code dcbo/src/utils/validation.js:24}),
	 * widened to also accept an empty string for the same reason as {@link #NIF_REGEXP}.
	 */
	static final String POSTAL_CODE_REGEXP = "^$|^\\d{4}-\\d{3}$";
}
