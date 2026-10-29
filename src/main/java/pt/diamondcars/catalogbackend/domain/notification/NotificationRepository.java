package pt.diamondcars.catalogbackend.domain.notification;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Notification}: {@link JpaSpecificationExecutor} to combine the
 * optional {@code read} filter of the notifications listing with pagination, plus the three bulk
 * statements of the back-office writes (TASK-006 AC E.3).
 *
 * <p>Why statements and not load-modify-save (what {@code dcbo-backend} did): "mark all as read"
 * loaded every unread notification into memory and issued one {@code UPDATE} per row; and a {@code
 * PATCH} or {@code DELETE} of a notification whose lead was deleted in the meantime (the {@code ON
 * DELETE CASCADE} of {@code notifications.lead_id}) affected 0 rows at flush, Hibernate threw {@code
 * StaleStateException} and the caller got a 500. A single statement returns how many rows it
 * touched, and that count alone decides the 404.
 *
 * <p>{@code clearAutomatically} so a notification read later in the same transaction (the body of
 * {@code PATCH /{id}/read}) comes from the database, not from a stale persistence context.
 */
public interface NotificationRepository
		extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

	/**
	 * Marks one notification as read. Idempotent: an already-read notification still counts as
	 * matched.
	 *
	 * @param id the notification's id
	 * @return 1 if the notification exists, 0 otherwise
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Notification n set n.read = true where n.id = :id")
	int markAsRead(@Param("id") UUID id);

	/**
	 * Marks every unread notification as read in one {@code UPDATE}.
	 *
	 * @return the number of notifications that were unread
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Notification n set n.read = true where n.read = false")
	int markAllAsRead();

	/**
	 * Deletes one notification in one {@code DELETE}.
	 *
	 * @param id the notification's id
	 * @return 1 if the notification existed, 0 otherwise
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from Notification n where n.id = :id")
	int deleteByIdReturningCount(@Param("id") UUID id);
}
