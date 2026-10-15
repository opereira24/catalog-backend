package pt.diamondcars.catalogbackend.web;

import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pt.diamondcars.catalogbackend.service.NotificationService;
import pt.diamondcars.catalogbackend.web.dto.NotificationResponse;

/**
 * Back-office notification bell (TASK-006, AC E.3) under {@code /api/backoffice/notifications}
 * ({@link BackOfficeApi#PREFIX}): list, mark one as read, mark all as read, delete. Ported from
 * {@code dcbo-backend}'s {@code NotificationController}, same verbs ({@code POST /read-all}, not
 * {@code PATCH}). No public twin, so no {@code BackOffice} prefix in the name.
 *
 * <p>Every endpoint needs a JWT and the {@code ADMIN} or {@code USER} role.
 */
@RestController
@RequestMapping(BackOfficeApi.PREFIX + "/notifications")
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class NotificationController {

	private final NotificationService notificationService;

	/**
	 * Creates the controller with its backing service.
	 *
	 * @param notificationService the service implementing every operation below
	 */
	public NotificationController(NotificationService notificationService) {
		this.notificationService = notificationService;
	}

	/**
	 * Lists notifications, most recently created first by default.
	 *
	 * @param read when given, only notifications with this {@code read} value; {@code abc} is a 400,
	 *     empty means no filter
	 * @param pageable pagination/sorting; defaults to 50 per page by {@code createdAt} DESC, then
	 *     {@code id} DESC; {@code sort} only accepts {@code NotificationService#SORTABLE_PROPERTIES}
	 * @return the requested page, in a {@link PagedModel}
	 */
	@GetMapping
	public PagedModel<NotificationResponse> list(
			@RequestParam(required = false) Boolean read,
			@PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return new PagedModel<>(notificationService.list(read, pageable));
	}

	/**
	 * Marks a notification as read. Idempotent.
	 *
	 * @param id the notification's id
	 * @return the notification (200), or a 404 {@code ApiError}
	 */
	@PatchMapping("/{id}/read")
	public NotificationResponse markAsRead(@PathVariable UUID id) {
		return notificationService.markAsRead(id);
	}

	/** Marks every unread notification as read. */
	@PostMapping("/read-all")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void markAllAsRead() {
		notificationService.markAllAsRead();
	}

	/**
	 * Deletes a notification.
	 *
	 * @param id the notification's id
	 */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		notificationService.delete(id);
	}
}
