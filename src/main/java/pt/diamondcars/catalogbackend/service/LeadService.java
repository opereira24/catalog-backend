package pt.diamondcars.catalogbackend.service;

import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.lead.LeadOrigin;
import pt.diamondcars.catalogbackend.domain.lead.LeadRepository;
import pt.diamondcars.catalogbackend.domain.lead.LeadStatus;
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
	private final CarRepository carRepository;
	private final ApplicationEventPublisher eventPublisher;

	/**
	 * Creates the service with its dependencies.
	 *
	 * @param leadRepository the repository leads are persisted through
	 * @param carRepository used to resolve a request's {@code carroId} to the car it refers to
	 * @param eventPublisher used to publish {@link LeadCreatedEvent} after every successful save
	 */
	public LeadService(
			LeadRepository leadRepository,
			CarRepository carRepository,
			ApplicationEventPublisher eventPublisher) {
		this.leadRepository = leadRepository;
		this.carRepository = carRepository;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Persists a lead submitted by the public site, sanitizing every free-text field (requirement
	 * 3) and deriving {@link LeadOrigin} from whether a car is referenced (requirement 2): {@link
	 * LeadOrigin#WEBSITE} when {@code carroId} is present, {@link LeadOrigin#WEBSITE_CONTACTO}
	 * otherwise — the exact two values the public site's own forms already use. The status is
	 * always {@link LeadStatus#ATIVO}, what the site has always written, set explicitly rather than
	 * left to the entity's builder default ({@link LeadStatus#CONTACTADO}). A {@code carroId} that
	 * matches no car still yields 201 (see {@link #findCar(UUID)}).
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
						.car(findCar(request.carroId()))
						.carroMarca(carroMarca)
						.carroModelo(carroModelo)
						.status(LeadStatus.ATIVO)
						.origem(request.carroId() != null ? LeadOrigin.WEBSITE : LeadOrigin.WEBSITE_CONTACTO)
						.build();
		Lead saved = leadRepository.save(lead);
		eventPublisher.publishEvent(new LeadCreatedEvent(saved.getId()));
		return new LeadResponse(saved.getId());
	}

	/**
	 * Resolves the car a lead is about, tolerating an id that matches no car (TASK-001).
	 *
	 * <p>{@code leads.car_id} is a foreign key since V2, so an unknown id can no longer be stored
	 * as is. A visitor may still send one (a car deleted while its page was open, a stale link), and
	 * the public contract never fails for that: the lead is saved without car, keeping the {@code
	 * carroMarca}/{@code carroModelo} snapshot, and {@code origem} stays decided by the presence of
	 * {@code carroId} in the request, not by whether the car exists.
	 *
	 * @param carroId the id sent by the public site, or {@code null} for a general contact
	 * @return the matching car, or {@code null} when {@code carroId} is {@code null} or unknown
	 */
	private Car findCar(UUID carroId) {
		return carroId == null ? null : carRepository.findById(carroId).orElse(null);
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
