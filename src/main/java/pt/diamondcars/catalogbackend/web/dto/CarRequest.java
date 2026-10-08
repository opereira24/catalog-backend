package pt.diamondcars.catalogbackend.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import pt.diamondcars.catalogbackend.web.dto.validation.MaxCurrentYearPlusOne;

/**
 * Request body of {@code POST /api/backoffice/cars} (TASK-003, AC C.1), and of the {@code PUT} that
 * TASK-010 adds on top of it. Ported from {@code dcbo-backend}, replicating the browser rules of
 * {@code dcbo/src/utils/validation.js} ({@code carValidationSchema}) so the API never stores what
 * the form would already reject. JSON keys are exactly the ones {@code dcbo} writes (ADR-001, layer
 * 3), {@code isConsignacao} included.
 *
 * <p>Differences from {@code dcbo-backend}, each measured by the arquiteto:
 *
 * <ul>
 *   <li>{@code @Size} on {@code combustivel}/{@code transmissao}/{@code origem} and on every image
 *       URL, plus at most {@value #IMAGES_MAX} images: without them, a value longer than its column
 *       (or a {@code null} URL) reached the database and came back as 409 "Pedido em conflito", a
 *       wrong code for an input error; now it is a 400 naming the field.
 *   <li>{@link #isConsignacao} and {@link #destaque} are {@link Boolean}, not {@code boolean}: with
 *       a primitive, Jackson 3 ({@code FAIL_ON_NULL_FOR_PRIMITIVES} on by default in Boot 4) rejects
 *       a body that omits the key with a 400 that names no field, and the real {@code dcbo} form
 *       never sends {@code destaque}.
 *   <li>{@link #observacoes} is new (public text the site shows, had no writer).
 * </ul>
 *
 * <p>Null semantics (AC C.3). On create: {@code observacoes} null or blank is stored as {@code
 * null}; {@code destaque}/{@code isConsignacao} null are {@code false}; {@code images}/{@code
 * imageThumbnails} null are empty; {@code garantiaMeses} null is 0. TASK-010's {@code PUT} keeps the
 * stored {@code observacoes}, {@code destaque} and photos when the key is null or absent. Keys this
 * record does not declare ({@code vendido}, {@code reservado}, {@code precoVenda}, {@code
 * clienteId}, {@code id}, ...) are ignored: state only changes through its own endpoints.
 *
 * @param marca brand, required, max 100, no special characters ({@link #NO_SPECIAL_CHARS_REGEXP})
 * @param modelo model, required, max 100, no special characters
 * @param ano model year, required, from 1950 to next calendar year
 * @param preco sale price, required, from 100 to {@value #PRICE_MAX_VALUE}
 * @param km odometer reading, required, from 0 to 1 000 000
 * @param cor colour, required, max 50, no special characters
 * @param combustivel fuel type, required, max 50 (the column size)
 * @param transmissao gearbox, required, max 50 (the column size)
 * @param origem provenance, required, max 100 (the column size)
 * @param descricao free-text description, optional, max 1000
 * @param observacoes longer public notes, optional, max 1000 ({@code LIMITS.TEXT_LONG} of {@code
 *     dcbo}); null or blank is stored as {@code null}
 * @param precoCompra purchase price, optional, from 0 to {@value #PRICE_MAX_VALUE}
 * @param dataCompra purchase date ({@code yyyy-MM-dd}), optional
 * @param isConsignacao whether the car is sold on consignment for {@link #partnerId}; null is
 *     {@code false}
 * @param partnerId consignment partner, optional; an id that does not exist is a 404
 * @param commissionValue commission owed to the partner on sale, optional, from 0 to {@value
 *     #PRICE_MAX_VALUE}
 * @param garantiaMeses warranty in months, optional, at least 0; null is 0
 * @param destaque whether the car is featured; null is {@code false}; subject to the 8-car limit
 * @param images ordered photo URLs, at most {@value #IMAGES_MAX}, each non-blank and at most 1000
 *     characters; null is an empty list
 * @param imageThumbnails thumbnail URLs parallel to {@link #images} by index, at most {@value
 *     #IMAGES_MAX}, each at most 1000 characters (elements may be null; a shorter list leaves the
 *     remaining thumbnails null)
 */
public record CarRequest(
		@NotBlank @Size(max = 100) @Pattern(regexp = CarRequest.NO_SPECIAL_CHARS_REGEXP) String marca,
		@NotBlank @Size(max = 100) @Pattern(regexp = CarRequest.NO_SPECIAL_CHARS_REGEXP) String modelo,
		@NotNull @Min(1950) @MaxCurrentYearPlusOne Integer ano,
		@NotNull @DecimalMin("100") @DecimalMax(CarRequest.PRICE_MAX_VALUE) BigDecimal preco,
		@NotNull @Min(0) @Max(1_000_000) Integer km,
		@NotBlank @Size(max = 50) @Pattern(regexp = CarRequest.NO_SPECIAL_CHARS_REGEXP) String cor,
		@NotBlank @Size(max = 50) String combustivel,
		@NotBlank @Size(max = 50) String transmissao,
		@NotBlank @Size(max = 100) String origem,
		@Size(max = CarRequest.TEXT_LONG_MAX) String descricao,
		@Size(max = CarRequest.TEXT_LONG_MAX) String observacoes,
		@DecimalMin("0") @DecimalMax(CarRequest.PRICE_MAX_VALUE) BigDecimal precoCompra,
		LocalDate dataCompra,
		Boolean isConsignacao,
		UUID partnerId,
		@DecimalMin("0") @DecimalMax(CarRequest.PRICE_MAX_VALUE) BigDecimal commissionValue,
		@Min(0) Integer garantiaMeses,
		Boolean destaque,
		@Size(max = CarRequest.IMAGES_MAX) List<@NotBlank @Size(max = CarRequest.URL_MAX) String> images,
		@Size(max = CarRequest.IMAGES_MAX) List<@Size(max = CarRequest.URL_MAX) String> imageThumbnails) {

	/**
	 * Upper bound of {@link #preco}/{@link #precoCompra}/{@link #commissionValue}, {@code
	 * LIMITS.PRICE_MAX} of {@code dcbo}; also keeps values inside a {@code numeric(12,2)} column.
	 */
	static final String PRICE_MAX_VALUE = "10000000";

	/** Maximum photos per car, {@code LIMITS.IMAGES_MAX} of {@code dcbo} (the form blocks at 60). */
	static final int IMAGES_MAX = 60;

	/** Maximum length of a photo or thumbnail URL, the size of {@code car_images.url}. */
	static final int URL_MAX = 1000;

	/** Maximum length of {@link #descricao}/{@link #observacoes}, {@code LIMITS.TEXT_LONG} of {@code dcbo}. */
	static final int TEXT_LONG_MAX = 1000;

	/**
	 * {@code PATTERNS.NO_SPECIAL_CHARS} of {@code dcbo/src/utils/validation.js}, applied to {@link
	 * #marca}/{@link #modelo}/{@link #cor}: the public site renders them, so markup such as {@code
	 * <script>} is never accepted.
	 */
	static final String NO_SPECIAL_CHARS_REGEXP = "^[a-zA-Z0-9À-ÿ\\s\\-.,]+$";
}
