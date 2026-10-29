package pt.diamondcars.catalogbackend.service;

import org.springframework.data.jpa.domain.Specification;
import pt.diamondcars.catalogbackend.domain.notification.Notification;

/**
 * Builds the {@link Specification} of the optional {@code ?read=} filter of {@code GET
 * /api/backoffice/notifications} (TASK-006, AC E.3). Ported from {@code dcbo-backend}.
 */
final class NotificationSpecifications {

	private NotificationSpecifications() {}

	/**
	 * Matches every notification whose {@code read} equals the argument, or every notification when
	 * it is {@code null} (never "match nothing").
	 *
	 * @param read required value of {@code notifications.read}, or {@code null}
	 * @return the specification
	 */
	static Specification<Notification> matching(Boolean read) {
		return (root, query, criteriaBuilder) ->
				read == null ? criteriaBuilder.conjunction() : criteriaBuilder.equal(root.get("read"), read);
	}
}
