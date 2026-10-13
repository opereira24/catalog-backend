package pt.diamondcars.catalogbackend.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of {@code POST /api/backoffice/cars/{id}/sell} (TASK-010, AC C.5), ported from {@code
 * dcbo-backend} with the same JSON keys.
 *
 * @param precoVenda the price the car was sold for: required, between 100 and {@value
 *     CarRequest#PRICE_MAX_VALUE} (the floor and ceiling of {@code dcbo}'s {@code sellCar}; above
 *     the ceiling is a 400, never the 409 a {@code numeric(12,2)} overflow would give), and at most 2
 *     decimal places. The {@code @Digits} is new compared to {@code dcbo-backend}: {@code
 *     NUMERIC(12,2)} silently rounds {@code 20000.005} to {@code 20000.01} while the response echoed
 *     {@code 20000.005} (the TASK-014 defect). Hibernate Validator counts trailing zeros, so {@code
 *     20000.500} is also a 400; {@code dcbo} sends JavaScript numbers, which never have them.
 * @param clienteId id of the buying client, optional (a sale does not always have a known buyer)
 */
public record SellCarRequest(
		@NotNull @DecimalMin("100") @DecimalMax(CarRequest.PRICE_MAX_VALUE) @Digits(integer = 8, fraction = 2)
				BigDecimal precoVenda,
		UUID clienteId) {}
