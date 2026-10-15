package pt.diamondcars.catalogbackend.web.exception;

import java.util.UUID;

/**
 * Thrown when {@code POST /api/backoffice/cars/{id}/sell} targets a car that is already sold
 * (TASK-010, AC B.6/C.6), mapped to 409 by {@code ApiExceptionHandler}. Ported from {@code
 * dcbo-backend}, same message.
 *
 * <p>A sale is a one-way transition undone only by {@code revert-sale}. Re-applying it would count
 * the purchase twice for the same buyer or, with no {@code clienteId}, detach the car from its
 * original buyer and leave that client's {@code purchases_count} inflated for good. Thrown right
 * after the car's row lock and before any other lock or write, so a refused sale changes nothing.
 */
public class CarAlreadySoldException extends RuntimeException {

	/**
	 * Creates the exception with a message naming the car.
	 *
	 * @param carId id of the car that is already sold
	 */
	public CarAlreadySoldException(UUID carId) {
		super("Carro ja vendido: " + carId);
	}
}
