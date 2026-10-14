package pt.diamondcars.catalogbackend.web.exception;

/**
 * Thrown when {@code POST /api/backoffice/users} names an {@code authSubject} that already has a
 * local profile (TASK-007, AC C.2) — mapped by {@link
 * pt.diamondcars.catalogbackend.web.ApiExceptionHandler} to 409. Ported from {@code
 * dcbo-backend}'s exception of the same name.
 *
 * <p>Only the sequential case reaches this exception: two concurrent creations with the same
 * {@code authSubject} both pass the lookup, and the second one fails on the unique index {@code
 * idx_app_users_auth_subject} instead (409 {@code Pedido em conflito com o estado atual dos dados},
 * {@code service/AppUserConcurrencyTest} K-U4).
 */
public class DuplicateAuthSubjectException extends RuntimeException {

	/**
	 * Creates the exception with a human-readable message describing the conflict.
	 *
	 * @param message description to surface in the 409 response body's {@code message} field
	 */
	public DuplicateAuthSubjectException(String message) {
		super(message);
	}
}
