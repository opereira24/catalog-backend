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
	 * @param request the validated, public request payload
	 * @return the created lead's id
	 */
	@Transactional
	public LeadResponse createFromWebsite(LeadRequest request) {
		Lead lead =
				Lead.builder()
						.nome(TextSanitizer.sanitize(request.nome()))
						.email(blankToNull(request.email()))
						.telefone(request.telefone())
						.mensagem(TextSanitizer.sanitize(request.mensagem()))
						.carId(request.carroId())
						.carroMarca(TextSanitizer.sanitize(request.carroMarca()))
						.carroModelo(TextSanitizer.sanitize(request.carroModelo()))
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
}
