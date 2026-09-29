package pt.diamondcars.catalogbackend.web.exception;

import java.util.UUID;

/**
 * Thrown when a car looked up by id does not exist in the catalog, mapped to 404 by {@code
 * pt.diamondcars.catalogbackend.web.ApiExceptionHandler} (TASK-014 requirement 2/7).
 */
public class CarNotFoundException extends RuntimeException {

	/**
	 * Creates the exception for the given missing car id.
	 *
	 * @param id the id that did not match any car
	 */
	public CarNotFoundException(UUID id) {
		super("Carro nao encontrado: " + id);
	}
}
