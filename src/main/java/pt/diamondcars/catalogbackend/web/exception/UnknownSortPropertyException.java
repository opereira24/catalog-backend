package pt.diamondcars.catalogbackend.web.exception;

/**
 * Thrown when a back-office listing receives a {@code sort} property outside its allow-list
 * (TASK-003, AC D.3), before any query runs. Mapped to 400 by {@link
 * pt.diamondcars.catalogbackend.web.ApiExceptionHandler} with the same message as Spring Data's
 * {@code PropertyReferenceException} (the safety net for listings without an allow-list).
 *
 * <p>Why an allow-list and not just Spring Data's own check: a property that exists but crosses an
 * association ({@code images.url}, {@code partner.name}) is accepted by Spring Data and turns into
 * a join; on a collection that repeats the same car across pages and changes {@code totalElements}
 * from page to page (measured by the arquiteto on {@code dcbo-backend}).
 */
public class UnknownSortPropertyException extends RuntimeException {

	/**
	 * Creates the exception for the given rejected property.
	 *
	 * @param property the {@code sort} property as the caller sent it
	 */
	public UnknownSortPropertyException(String property) {
		super("Campo de ordenacao desconhecido: " + property);
	}
}
