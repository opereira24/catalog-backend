package pt.diamondcars.catalogbackend.web.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Request payload for {@code POST /api/leads} (TASK-015 requirement 1), the public,
 * unauthenticated entry point for every sales lead the {@code dc} site submits — mirrors the
 * shape {@code dc/src/services/firebaseService.js:91,118} already sends for {@code createLead}
 * (car-specific) and {@code createGeneralContact} (general contact, no {@link #carroId}).
 *
 * <p><b>TASK-015 review r1, BLOQUEADOR 1</b>: the sizes/pattern declared here were aligned with
 * {@code dcbo-backend}'s {@code InternalLeadRequest} while leads were forwarded there (until
 * TASK-006). They are part of the public contract the {@code dc} relies on and did not change when
 * forwarding was removed. Two consequences:
 *
 * <ul>
 *   <li>{@link #telefone} is normalized (whitespace/hyphens stripped) by the compact constructor
 *       below <b>before</b> {@link #telefone}'s own {@link Pattern} constraint runs, so a value
 *       shaped exactly like the public site's own placeholder ({@code dc/src/pages/Contact.js:232},
 *       {@code "+351 912 345 678"}) is accepted and stored in its normalized form.
 *   <li>{@link #nome}/{@link #mensagem}/{@link #carroMarca}/{@link #carroModelo} carry only a
 *       generous, purely defensive raw-input {@code @Size} cap here (against pathological payload
 *       sizes) — the actual business-length limits (equal to or narrower than the {@code leads}
 *       columns) are enforced by {@code LeadService#createFromWebsite} <b>after</b> sanitizing,
 *       never before: validating before sanitizing let a message that fit under 1000 raw
 *       characters balloon past that same limit once {@code /}, {@code "}, etc. were
 *       HTML-entity-escaped.
 * </ul>
 *
 * @param nome full name, required, actual 2-100 character bound enforced post-sanitization by
 *     {@code LeadService}
 * @param telefone contact phone number, required, normalized then validated against the exact
 *     Portuguese mobile/landline format
 * @param email contact email, optional, but must be a valid address when present, and may never
 *     contain {@code < > " '} (TASK-015 review r1, IMPORTANTE 2: closes the quoted-local-part XSS
 *     vector {@code @Email} alone lets through, e.g. {@code "<svg/onload=...>"@x.pt})
 * @param mensagem free-text message, optional, stored in {@code leads.mensagem}, actual &le;1000
 *     character bound enforced post-sanitization by {@code LeadService}
 * @param carroId id of the car this lead is about, or {@code null} for a general contact lead —
 *     presence/absence decides {@link pt.diamondcars.catalogbackend.domain.lead.LeadOrigin}
 *     (requirement 2)
 * @param carroMarca denormalized brand snapshot of {@link #carroId}, or {@code null}, actual
 *     &le;100 character bound enforced post-sanitization by {@code LeadService}
 * @param carroModelo denormalized model snapshot of {@link #carroId}, or {@code null}, actual
 *     &le;100 character bound enforced post-sanitization by {@code LeadService}
 */
public record LeadRequest(
		@NotBlank @Size(max = 2000) String nome,
		@NotBlank @Pattern(
						regexp = LeadRequest.PHONE_REGEXP,
						message = "formato de telefone invalido (ex: 912345678 ou +351912345678)")
				String telefone,
		@Email @Size(max = 255) @Pattern(regexp = LeadRequest.NO_MARKUP_REGEXP, message = "email contem caracteres nao permitidos")
				String email,
		@Size(max = 5000) String mensagem,
		UUID carroId,
		@Size(max = 500) String carroMarca,
		@Size(max = 500) String carroModelo) {

	/**
	 * Portuguese mobile or landline number, optionally with {@code +351} — validated here against the already-normalized value (see the compact constructor below), so it also
	 * matches a value that arrived with internal spaces or hyphens.
	 */
	static final String PHONE_REGEXP = "^(\\+351)?[29]\\d{8}$";

	/**
	 * Rejects {@code < > " '}, the characters an HTML/JS injection needs, anywhere in the value —
	 * used on {@link #email}, which is never run through {@code TextSanitizer} (an escaped-but-still
	 * -present injection in an email address is not something the back-office or any downstream
	 * consumer expects to have to re-sanitize).
	 */
	static final String NO_MARKUP_REGEXP = "^[^<>\"']*$";

	/**
	 * Normalizes {@link #telefone} by stripping whitespace and hyphens before either this record's
	 * own {@link Pattern} constraint validates it (TASK-015 review r1, BLOQUEADOR 1) — runs before
	 * field assignment, so the value every constraint annotation ultimately sees, and the value
	 * persisted, are the same normalized string.
	 */
	public LeadRequest {
		telefone = normalizePhone(telefone);
	}

	/**
	 * Strips every space, tab and hyphen from a raw phone number.
	 *
	 * @param value the raw value, or {@code null}
	 * @return {@code null} when {@code value} is {@code null}; otherwise {@code value} with every
	 *     whitespace character and hyphen removed
	 */
	private static String normalizePhone(String value) {
		return value == null ? null : value.replaceAll("[\\s-]", "");
	}
}
