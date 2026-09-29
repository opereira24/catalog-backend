package pt.diamondcars.catalogbackend.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarImage;

/**
 * Response payload for every {@code GET /api/cars*} endpoint (TASK-014), covering exactly the
 * fields TASK-013 requirement 1 lists as what the public site needs — never the {@link Car}
 * entity itself and never {@link Car#getSyncedAt()}, which is an internal sync-diagnosis field
 * (requirement 4).
 *
 * @param id the car's identifier
 * @param _id the same value as {@link #id}, kept only so {@code dc} can migrate without a
 *     regression while it still reads {@code car._id} in some places ({@code
 *     dc/src/services/firebaseService.js:81}) and {@code car.id} in others — deprecated, to be
 *     removed once the release closes (TASK-014, {@code ## Notas})
 * @param marca brand
 * @param modelo model
 * @param ano model year
 * @param preco sale price
 * @param km odometer reading
 * @param cor colour
 * @param combustivel fuel type
 * @param descricao short, customer-facing description, possibly {@code null}
 * @param observacoes longer free-text notes shown on the detail page, possibly {@code null}
 * @param garantiaMeses warranty length in months
 * @param vendido whether this car has been sold
 * @param reservado whether this car is currently reserved
 * @param destaque whether this car is currently featured on the homepage
 * @param images ordered list of photo URLs (by {@code position})
 * @param createdAt creation timestamp
 * @param updatedAt last-update timestamp
 */
public record CarResponse(
		UUID id,
		UUID _id,
		String marca,
		String modelo,
		int ano,
		BigDecimal preco,
		int km,
		String cor,
		String combustivel,
		String descricao,
		String observacoes,
		int garantiaMeses,
		boolean vendido,
		boolean reservado,
		boolean destaque,
		List<String> images,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	/**
	 * Builds the response for a given, fully-loaded {@link Car}.
	 *
	 * <p>Must only be called while the {@link Car}'s persistence context is still open (i.e. from
	 * within the {@code @Transactional} service method that loaded it): {@link Car#getImages()} is
	 * lazily fetched, and accessing it after the session closes would raise a {@code
	 * LazyInitializationException}.
	 *
	 * @param car the car to map, never {@code null}
	 * @return the corresponding response DTO
	 */
	public static CarResponse from(Car car) {
		List<String> images = car.getImages().stream().map(CarImage::getUrl).toList();
		return new CarResponse(
				car.getId(),
				car.getId(),
				car.getMarca(),
				car.getModelo(),
				car.getAno(),
				car.getPreco(),
				car.getKm(),
				car.getCor(),
				car.getCombustivel(),
				car.getDescricao(),
				car.getObservacoes(),
				car.getGarantiaMeses(),
				car.isVendido(),
				car.isReservado(),
				car.isDestaque(),
				images,
				car.getCreatedAt(),
				car.getUpdatedAt());
	}
}
