package pt.diamondcars.catalogbackend.web.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body of {@code PATCH /api/backoffice/cars/{id}/highlight} (TASK-003, AC A.2), equivalent
 * to the {@code onUpdateCar} call of {@code dcbo/src/pages/highlights.js}. Ported from {@code
 * dcbo-backend}.
 *
 * @param destaque {@code true} to feature the car, {@code false} to remove it from the featured
 *     set; required ({@code {}} is a 400). Turning it on for a car that is not featured yet is
 *     rejected with 409 once 8 cars are featured
 */
public record HighlightRequest(@NotNull Boolean destaque) {}
