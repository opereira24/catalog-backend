package pt.diamondcars.catalogbackend.domain.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Spring Data repository for {@link Notification}, ported unchanged from {@code dcbo-backend}
 * (TASK-001): {@link JpaSpecificationExecutor} to combine the optional {@code read} filter of the
 * notifications listing with pagination, and {@link #findByReadFalse()} for "mark all as read".
 */
public interface NotificationRepository
		extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

	/**
	 * Lists every unread notification, so "mark all as read" can flip every one of them to {@code
	 * read = true} within a single transaction.
	 *
	 * @return every {@link Notification} with {@code read = false}
	 */
	List<Notification> findByReadFalse();
}
