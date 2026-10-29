package pt.diamondcars.catalogbackend.web.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import pt.diamondcars.catalogbackend.domain.notification.Notification;

/**
 * Response payload of every {@code /api/backoffice/notifications} endpoint (TASK-006, AC E.3),
 * exactly 8 JSON keys, the same as {@code dcbo-backend}'s {@code NotificationResponse}. Never the
 * JPA entity itself.
 *
 * <p>{@link #leadId} is English in JSON too: no {@code dcbo} key pins it to Portuguese (ADR-001,
 * layer 3, as decided in {@code dcbo-backend}).
 *
 * @param id the notification's identifier
 * @param tipo the notification's type, e.g. {@code novo_lead}, {@code follow_up}
 * @param titulo short title, or {@code null}
 * @param mensagem the notification's message
 * @param prioridade priority, e.g. {@code alta}/{@code media}, or {@code null}
 * @param leadId the lead this notification is about, or {@code null}
 * @param read whether this notification has been marked as read
 * @param createdAt creation timestamp
 */
public record NotificationResponse(
		UUID id,
		String tipo,
		String titulo,
		String mensagem,
		String prioridade,
		UUID leadId,
		boolean read,
		OffsetDateTime createdAt) {

	/**
	 * Builds the response for a {@link Notification}. Call only inside the {@code @Transactional}
	 * service method that loaded it.
	 *
	 * <p>Reads only {@code getId()} of the {@code LAZY} lead proxy, which Hibernate answers without
	 * loading the lead: listing 50 notifications costs the page and {@code count} queries, never one
	 * query per lead ({@code NotificationControllerTest}).
	 *
	 * @param notification the notification to map, never {@code null}
	 * @return the corresponding response DTO
	 */
	public static NotificationResponse from(Notification notification) {
		return new NotificationResponse(
				notification.getId(),
				notification.getTipo(),
				notification.getTitulo(),
				notification.getMensagem(),
				notification.getPrioridade(),
				notification.getLead() != null ? notification.getLead().getId() : null,
				notification.isRead(),
				notification.getCreatedAt());
	}
}
