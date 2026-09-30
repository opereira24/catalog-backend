package pt.diamondcars.catalogbackend.service;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.lead.LeadOrigin;
import pt.diamondcars.catalogbackend.domain.lead.LeadRepository;
import pt.diamondcars.catalogbackend.util.TextSanitizer;
import pt.diamondcars.catalogbackend.web.dto.LeadRequest;
import pt.diamondcars.catalogbackend.web.dto.LeadResponse;
import pt.diamondcars.catalogbackend.web.exception.LeadValidationException;

/**
 * Backing service for {@code PublicLeadController} (TASK-015): persists every public-site lead
 * locally — the {@code leads} table doubles as an outbox (ASSUNÇÃO, {@code
 * backlog/tasks/TASK-015.md}, {@code ## Notas}: this is the only way to never lose a contact when
 * {@code dcbo-backend} is down) — then publishes {@link LeadCreatedEvent} so {@link
 * LeadForwarder} attempts the best-effort forward strictly after commit (requirement 6).
 */
@Service
public class LeadService {

	private final LeadRepository leadRepository;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * Creates the service with its dependencies.
	 *
	 * @param leadRepository the repository leads are persisted through
	 * @param eventPublisher used to publish {@link LeadCreatedEvent} after every successful save
	 */
	public LeadService(LeadRepository leadRepository, ApplicationEventPublisher eventPublisher) {
		this.leadRepository = leadRepository;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Persists a lead submitted by the public site, sanitizing every free-text field (requirement
	 * 3) and deriving {@link LeadOrigin} from whether a car is referenced (requirement 2): {@link
	 * LeadOrigin#WEBSITE} when {@code carroId} is present, {@link LeadOrigin#WEBSITE_CONTACTO}
	 * otherwise — the exact two values the public site's own forms already use.
	 *
	 * <p>Every length bound is checked <b>after</b> sanitizing (TASK-015 review r1, BLOQUEADOR 1):
	 * checking before let a value grow past {@code dcbo-backend}'s own limit once HTML-entity
	 * escaping expanded it (e.g. {@code /} becomes {@code &#x2F;}), so a lead accepted here with
	 * 201 could still be silently lost when the forwarding call to {@code POST /internal/leads}
	 * rejected it with 400. The bounds below are exactly {@code InternalLeadRequest}'s, confirmed
	 * in {@code dcbo-backend}'s source, so a lead persisted here can never fail forwarding on
	 * length/shape alone.
	 *
	 * @param request the validated, public request payload
	 * @return the created lead's id
	 * @throws LeadValidationException when {@code nome}, {@code mensagem}, {@code carroMarca} or
	 *     {@code carroModelo}, once sanitized, no longer fits the bound {@code dcbo-backend} also
	 *     enforces
	 */
	@Transactional
	public LeadResponse createFromWebsite(LeadRequest request) {
		String nome = TextSanitizer.sanitize(request.nome());
		String mensagem = TextSanitizer.sanitize(request.mensagem());
		String carroMarca = TextSanitizer.sanitize(request.carroMarca());
		String carroModelo = TextSanitizer.sanitize(request.carroModelo());
		validateLength("nome", nome, 2, 100);
		validateLength("mensagem", mensagem, 0, 1000);
		validateLength("carroMarca", carroMarca, 0, 100);
		validateLength("carroModelo", carroModelo, 0, 100);

		Lead lead =
				Lead.builder()
						.nome(nome)
						.email(blankToNull(request.email()))
						// telefone is already normalized to digits/"+" only by LeadRequest's compact
						// constructor and validated by its @Pattern constraint (requirement 3, IMPORTANTE
						// 2): sanitizing it too is a defensive no-op, kept for symmetry with the other
						// free-text fields rather than because it can ever actually change the value.
						.telefone(TextSanitizer.sanitize(request.telefone()))
						.mensagem(mensagem)
						.carId(request.carroId())
						.carroMarca(carroMarca)
						.carroModelo(carroModelo)
						.origem(request.carroId() != null ? LeadOrigin.WEBSITE : LeadOrigin.WEBSITE_CONTACTO)
						.build();
		Lead saved = leadRepository.save(lead);
		eventPublisher.publishEvent(new LeadCreatedEvent(saved.getId()));
		return new LeadResponse(saved.getId());
	}

	/**
	 * Normalizes a blank optional field to {@code null}, so an empty string never overwrites the
	 * column with an empty value instead of leaving it genuinely unset.
	 *
	 * @param value the raw optional field value
	 * @return {@code null} when {@code value} is {@code null} or blank, otherwise {@code value}
	 *     unchanged
	 */
	private static String blankToNull(String value) {
		return (value == null || value.isBlank()) ? null : value;
	}

	/**
	 * Validates that a sanitized field's length is within {@code [min, max]}, skipping the check
	 * entirely when the field is {@code null} (an absent optional field, e.g. {@code mensagem}).
	 *
	 * @param field the field's name, exactly as it appears in {@link LeadRequest}
	 * @param value the already-sanitized value to check
	 * @param min the minimum accepted length, inclusive
	 * @param max the maximum accepted length, inclusive
	 * @throws LeadValidationException when {@code value} is non-null and its length falls outside
	 *     {@code [min, max]}
	 */
	private static void validateLength(String field, String value, int min, int max) {
		if (value == null) {
			return;
		}
		if (value.length() < min || value.length() > max) {
			throw new LeadValidationException(
					field, "deve ter entre " + min + " e " + max + " caracteres apos sanitizacao");
		}
	}
}
