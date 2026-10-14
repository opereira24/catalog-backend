package pt.diamondcars.catalogbackend.service;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.notification.Notification;
import pt.diamondcars.catalogbackend.domain.notification.NotificationRepository;
import pt.diamondcars.catalogbackend.web.dto.NotificationResponse;
import pt.diamondcars.catalogbackend.web.exception.ResourceNotFoundException;
import pt.diamondcars.catalogbackend.web.exception.UnknownSortPropertyException;

/**
 * Business logic of {@code /api/backoffice/notifications} (TASK-006, AC E.3), ported from {@code
 * dcbo-backend}'s {@code NotificationService}, plus {@link #createForNewLead(Lead)}, which {@code
 * LeadService#createFromWebsite} calls inside its own transaction (AC C.1).
 *
 * <p>Read-only transaction by default; every write method is {@code @Transactional} and maps to a DTO
 * before returning ({@code spring.jpa.open-in-view: false}). The three writes on existing
 * notifications are single statements ({@link NotificationRepository}): the number of rows they
 * touch, not a prior load, decides the 404, so a notification deleted in the meantime (also by the
 * {@code ON DELETE CASCADE} of its lead) is a 404 and never a 500.
 */
@Service
@Transactional(readOnly = true)
public class NotificationService {

	/**
	 * Properties a listing may sort by: only the scalar columns of {@link Notification}, which has
	 * the {@code LAZY} association {@code lead} ({@code sort=lead.nome} would be a join; see {@code
	 * web/CLAUDE.md}).
	 */
	static final Set<String> SORTABLE_PROPERTIES = Set.of("tipo", "titulo", "prioridade", "read", "createdAt", "id");

	/** {@code tipo} of the notification that accompanies every lead of the public site. */
	static final String NEW_LEAD_TIPO = "novo_lead";

	private final NotificationRepository notificationRepository;

	/**
	 * Creates the service with its backing repository.
	 *
	 * @param notificationRepository persistence for {@link Notification}
	 */
	public NotificationService(NotificationRepository notificationRepository) {
		this.notificationRepository = notificationRepository;
	}

	/**
	 * Lists notifications, optionally filtered by {@code read}.
	 *
	 * @param read required value of {@code read}, or {@code null} to not filter by it
	 * @param pageable pagination and sorting (the controller defaults to {@code createdAt} DESC, 50 per
	 *     page); {@code id} DESC is appended when the sort lacks it ({@link
	 *     StableSort#withIdTiebreak}), after the allow-list check
	 * @return the requested page
	 * @throws UnknownSortPropertyException if a sort property is outside {@link #SORTABLE_PROPERTIES}
	 *     (mapped to 400), checked before any query
	 */
	public Page<NotificationResponse> list(Boolean read, Pageable pageable) {
		requireSortable(pageable.getSort());
		return notificationRepository
				.findAll(NotificationSpecifications.matching(read), StableSort.withIdTiebreak(pageable))
				.map(NotificationResponse::from);
	}

	/**
	 * Marks a notification as read. Idempotent: an already-read notification answers the same.
	 *
	 * @param id the notification's id
	 * @return the notification as stored after the update
	 * @throws ResourceNotFoundException if no notification has this id (mapped to 404)
	 */
	@Transactional
	public NotificationResponse markAsRead(UUID id) {
		if (notificationRepository.markAsRead(id) == 0) {
			throw notFound(id);
		}
		// The UPDATE above holds the row lock until commit: the row is still there to be read back.
		return NotificationResponse.from(notificationRepository.findById(id).orElseThrow(() -> notFound(id)));
	}

	/** Marks every unread notification as read, in one {@code UPDATE}. */
	@Transactional
	public void markAllAsRead() {
		notificationRepository.markAllAsRead();
	}

	/**
	 * Deletes a notification.
	 *
	 * @param id the notification's id
	 * @throws ResourceNotFoundException if no notification has this id (mapped to 404)
	 */
	@Transactional
	public void delete(UUID id) {
		if (notificationRepository.deleteByIdReturningCount(id) == 0) {
			throw notFound(id);
		}
	}

	/**
	 * Creates the notification that accompanies a new lead of the public site (TASK-006, AC C.3), in
	 * the caller's transaction (propagation {@code REQUIRED}): the lead and its notification are
	 * committed, or rolled back, together.
	 *
	 * <p>{@code mensagem} is {@code "Novo lead de <nome>"}, followed by {@code " sobre <marca>
	 * <modelo>"} with only the non-blank parts, and only when at least one is non-blank. {@code
	 * dcbo-backend} wrote {@code "sobre BMW null"} for a missing model and {@code "sobre  "} for the
	 * {@code carroMarca: ""} that {@code dc} sends with a general contact.
	 *
	 * @param lead the lead just saved, with its fields already sanitized
	 * @return the saved notification
	 */
	@Transactional
	public Notification createForNewLead(Lead lead) {
		return notificationRepository.save(Notification.builder()
				.tipo(NEW_LEAD_TIPO)
				.titulo("Novo Lead")
				.mensagem(newLeadMessage(lead))
				.prioridade("media")
				.lead(lead)
				.read(false)
				.build());
	}

	private static String newLeadMessage(Lead lead) {
		String car = Stream.of(lead.getCarroMarca(), lead.getCarroModelo())
				.filter(part -> part != null && !part.isBlank())
				.collect(Collectors.joining(" "));
		return "Novo lead de " + lead.getNome() + (car.isEmpty() ? "" : " sobre " + car);
	}

	private static void requireSortable(Sort sort) {
		for (Sort.Order order : sort) {
			if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
				throw new UnknownSortPropertyException(order.getProperty());
			}
		}
	}

	private static ResourceNotFoundException notFound(UUID id) {
		return new ResourceNotFoundException("Notificacao nao encontrada: " + id);
	}
}
