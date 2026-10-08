package pt.diamondcars.catalogbackend.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarImage;

/**
 * The full car as the back-office sees it, returned by every {@code /api/backoffice/cars} endpoint
 * (TASK-003, AC C.5): exactly 28 keys, the 27 of {@code dcbo-backend}'s {@code CarResponse} plus
 * {@code observacoes}. Internal fields ({@code precoCompra}, consignment, partner, client, sale) are
 * here and only here; the public {@link CarResponse} lists its own 18 keys and is untouched.
 *
 * <p>No {@code _id} (only the public projection has it) and no {@code syncedAt}. {@code
 * partnerId}/{@code clienteId} keep the frontend's JSON names over the English associations
 * (ADR-001, layer 3).
 *
 * @param id the car's identifier
 * @param marca brand
 * @param modelo model
 * @param ano model year
 * @param preco sale price
 * @param km odometer reading
 * @param cor colour
 * @param combustivel fuel type
 * @param transmissao gearbox
 * @param origem provenance
 * @param descricao free-text description, possibly {@code null}
 * @param observacoes longer public notes, possibly {@code null}
 * @param precoCompra purchase price, possibly {@code null}
 * @param dataCompra purchase date, possibly {@code null}
 * @param isConsignacao whether the car is sold on consignment
 * @param partnerId consignment partner, or {@code null}
 * @param commissionValue commission owed to the partner, or {@code null}
 * @param garantiaMeses warranty in months
 * @param destaque whether the car is featured
 * @param vendido whether the car was sold
 * @param reservado whether the car is reserved
 * @param dataVenda when the car was sold, or {@code null}
 * @param precoVenda the actual sale price, or {@code null}
 * @param clienteId the buying client, or {@code null}
 * @param images ordered photo URLs
 * @param imageThumbnails thumbnail URLs parallel to {@link #images}
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 */
public record BackOfficeCarResponse(
		UUID id,
		String marca,
		String modelo,
		int ano,
		BigDecimal preco,
		int km,
		String cor,
		String combustivel,
		String transmissao,
		String origem,
		String descricao,
		String observacoes,
		BigDecimal precoCompra,
		LocalDate dataCompra,
		boolean isConsignacao,
		UUID partnerId,
		BigDecimal commissionValue,
		int garantiaMeses,
		boolean destaque,
		boolean vendido,
		boolean reservado,
		OffsetDateTime dataVenda,
		BigDecimal precoVenda,
		UUID clienteId,
		List<String> images,
		List<String> imageThumbnails,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	/**
	 * Maps a car. Must run inside the transaction that loaded it ({@code open-in-view: false}): the
	 * photos are lazy. Partner and client are read with {@code getId()} on the lazy proxy, which
	 * Hibernate answers without loading the row, so a listing never loads a partner or a client
	 * (TASK-003, AC D.4).
	 *
	 * @param car the car to map, never {@code null}
	 * @return the response
	 */
	public static BackOfficeCarResponse from(Car car) {
		List<CarImage> carImages = car.getImages();
		return new BackOfficeCarResponse(
				car.getId(),
				car.getMarca(),
				car.getModelo(),
				car.getAno(),
				car.getPreco(),
				car.getKm(),
				car.getCor(),
				car.getCombustivel(),
				car.getTransmissao(),
				car.getOrigem(),
				car.getDescricao(),
				car.getObservacoes(),
				car.getPrecoCompra(),
				car.getDataCompra(),
				car.isConsignacao(),
				car.getPartner() != null ? car.getPartner().getId() : null,
				car.getCommissionValue(),
				car.getGarantiaMeses(),
				car.isDestaque(),
				car.isVendido(),
				car.isReservado(),
				car.getDataVenda(),
				car.getPrecoVenda(),
				car.getClient() != null ? car.getClient().getId() : null,
				carImages.stream().map(CarImage::getUrl).toList(),
				carImages.stream().map(CarImage::getThumbnailUrl).toList(),
				car.getCreatedAt(),
				car.getUpdatedAt());
	}
}
