package pt.diamondcars.catalogbackend.service;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarImage;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.partner.Partner;
import pt.diamondcars.catalogbackend.domain.partner.PartnerRepository;
import pt.diamondcars.catalogbackend.web.dto.BackOfficeCarResponse;
import pt.diamondcars.catalogbackend.web.dto.CarRequest;
import pt.diamondcars.catalogbackend.web.dto.HighlightRequest;
import pt.diamondcars.catalogbackend.web.exception.HighlightLimitExceededException;
import pt.diamondcars.catalogbackend.web.exception.ResourceNotFoundException;
import pt.diamondcars.catalogbackend.web.exception.UnknownSortPropertyException;

/**
 * Business logic of the back-office car API ({@code /api/backoffice/cars}, TASK-003): list, get,
 * create, reserve, release a reservation and feature. Ported from {@code dcbo-backend}'s {@code
 * CarService}; update, delete, sell and revert-sale come with TASK-010.
 *
 * <p>Every method maps to {@link BackOfficeCarResponse} inside its own transaction and never
 * returns the entity: with {@code open-in-view: false}, the lazy photos would fail after it.
 *
 * <p>None of these operations touches a partner's {@code cars_count}/{@code total_commission}, a
 * client's {@code purchases_count} or creates a transaction: those only change on sale and
 * reversal (TASK-010), as in {@code dcbo-backend}.
 */
@Service
public class BackOfficeCarService {

	/**
	 * Maximum number of featured cars ({@code destaque = true}) at the same time, the limit {@code
	 * dcbo/src/pages/highlights.js} enforces in the browser today. Counts every featured car, sold
	 * ones included, like {@code dcbo-backend} and {@code dcbo}.
	 */
	static final int HIGHLIGHT_LIMIT = 8;

	/**
	 * Properties the listing accepts in {@code sort} (TASK-003, AC D.3): only scalar columns of
	 * {@link Car}. Anything else is a 400 before the query runs; in particular a path through an
	 * association ({@code images.url}, {@code partner.name}, {@code client.name}) would be accepted
	 * by Spring Data as a join, and a join to the photos repeats the same car across pages.
	 */
	static final Set<String> SORTABLE_PROPERTIES = Set.of(
			"id", "createdAt", "updatedAt", "marca", "modelo", "ano", "preco", "km", "cor", "combustivel",
			"transmissao", "origem", "garantiaMeses", "vendido", "reservado", "destaque", "dataCompra",
			"dataVenda", "precoCompra", "precoVenda", "commissionValue");

	private final CarRepository carRepository;
	private final PartnerRepository partnerRepository;

	/**
	 * Creates the service with its repositories.
	 *
	 * @param carRepository persistence of {@link Car}, and the highlight advisory lock
	 * @param partnerRepository looked up to resolve {@link CarRequest#partnerId()}
	 */
	public BackOfficeCarService(CarRepository carRepository, PartnerRepository partnerRepository) {
		this.carRepository = carRepository;
		this.partnerRepository = partnerRepository;
	}

	/**
	 * Lists cars, optionally filtered by {@code vendido}/{@code reservado}/{@code destaque} (combined
	 * with AND).
	 *
	 * @param vendido required value of {@code vendido}, or {@code null} to not filter by it
	 * @param reservado required value of {@code reservado}, or {@code null} to not filter by it
	 * @param destaque required value of {@code destaque}, or {@code null} to not filter by it
	 * @param pageable page, size (capped globally by {@code spring.data.web.pageable.max-page-size})
	 *     and sort, defaulted by the controller
	 * @return the requested page
	 * @throws UnknownSortPropertyException if a sort property is not in {@link #SORTABLE_PROPERTIES}
	 *     (mapped to 400), checked before any query
	 */
	@Transactional(readOnly = true)
	public Page<BackOfficeCarResponse> list(
			Boolean vendido, Boolean reservado, Boolean destaque, Pageable pageable) {
		requireSortable(pageable.getSort());
		return carRepository
				.findAll(BackOfficeCarSpecifications.matching(vendido, reservado, destaque), pageable)
				.map(BackOfficeCarResponse::from);
	}

	/**
	 * Fetches a single car.
	 *
	 * @param id the car's id
	 * @return the car
	 * @throws ResourceNotFoundException if no car has this id (mapped to 404)
	 */
	@Transactional(readOnly = true)
	public BackOfficeCarResponse get(UUID id) {
		return BackOfficeCarResponse.from(findOrThrow(id));
	}

	/**
	 * Creates a car from the request, with the null semantics of {@link CarRequest} (AC C.3). A new
	 * car is never sold nor reserved and has no client: those keys are not part of the request.
	 *
	 * @param request the validated request
	 * @return the created car
	 * @throws ResourceNotFoundException if {@link CarRequest#partnerId()} does not exist (mapped to
	 *     404)
	 * @throws HighlightLimitExceededException if the car is created featured and 8 cars already are
	 *     (mapped to 409)
	 */
	@Transactional
	public BackOfficeCarResponse create(CarRequest request) {
		Partner partner = request.partnerId() != null ? requirePartner(request.partnerId()) : null;
		boolean destaque = Boolean.TRUE.equals(request.destaque());
		assertHighlightLimitRespected(false, destaque);

		Car car = Car.builder()
				.marca(request.marca())
				.modelo(request.modelo())
				.ano(request.ano())
				.preco(request.preco())
				.km(request.km())
				.cor(request.cor())
				.combustivel(request.combustivel())
				.transmissao(request.transmissao())
				.origem(request.origem())
				.descricao(request.descricao())
				.observacoes(blankToNull(request.observacoes()))
				.precoCompra(request.precoCompra())
				.dataCompra(request.dataCompra())
				.consignacao(Boolean.TRUE.equals(request.isConsignacao()))
				.partner(partner)
				.commissionValue(request.commissionValue())
				.garantiaMeses(request.garantiaMeses() != null ? request.garantiaMeses() : 0)
				.destaque(destaque)
				.build();
		addImages(car, request.images(), request.imageThumbnails());
		return BackOfficeCarResponse.from(carRepository.saveAndFlush(car));
	}

	/**
	 * Reserves a car. Idempotent: reserving a reserved car changes nothing.
	 *
	 * @param id the car's id
	 * @return the car
	 * @throws ResourceNotFoundException if no car has this id (mapped to 404)
	 */
	@Transactional
	public BackOfficeCarResponse reserve(UUID id) {
		Car car = findOrThrow(id);
		car.setReservado(true);
		return BackOfficeCarResponse.from(carRepository.saveAndFlush(car));
	}

	/**
	 * Releases a car's reservation. Idempotent: releasing a car that is not reserved changes
	 * nothing.
	 *
	 * @param id the car's id
	 * @return the car
	 * @throws ResourceNotFoundException if no car has this id (mapped to 404)
	 */
	@Transactional
	public BackOfficeCarResponse releaseReservation(UUID id) {
		Car car = findOrThrow(id);
		car.setReservado(false);
		return BackOfficeCarResponse.from(carRepository.saveAndFlush(car));
	}

	/**
	 * Features a car or removes it from the featured set, enforcing the 8-car limit. Removing frees
	 * a slot; featuring an already featured car does not count against the limit.
	 *
	 * @param id the car's id
	 * @param request the requested state ({@code destaque} is never null after validation)
	 * @return the car
	 * @throws ResourceNotFoundException if no car has this id (mapped to 404)
	 * @throws HighlightLimitExceededException if featuring it would exceed the limit (mapped to 409)
	 */
	@Transactional
	public BackOfficeCarResponse highlight(UUID id, HighlightRequest request) {
		Car car = findOrThrow(id);
		boolean destaque = Boolean.TRUE.equals(request.destaque());
		assertHighlightLimitRespected(car.isDestaque(), destaque);
		car.setDestaque(destaque);
		return BackOfficeCarResponse.from(carRepository.saveAndFlush(car));
	}

	/**
	 * Refuses to feature one more car once {@link #HIGHLIGHT_LIMIT} are featured. Only when the
	 * request turns the highlight on for a car that does not have it, it first takes the advisory
	 * lock ({@link CarRepository#lockHighlightSlots()}), then counts: concurrent requests are
	 * serialised and each count sees the highlight the previous one committed (AC E.2). Must run
	 * before any write of the calling transaction, which is what rules out a deadlock between two
	 * holders.
	 *
	 * @param alreadyFeatured whether the car is featured today ({@code false} for a new car)
	 * @param requestedDestaque the state the request asks for
	 * @throws HighlightLimitExceededException if the limit is already reached
	 */
	private void assertHighlightLimitRespected(boolean alreadyFeatured, boolean requestedDestaque) {
		if (!requestedDestaque || alreadyFeatured) {
			return;
		}
		carRepository.lockHighlightSlots();
		if (carRepository.countByDestaqueTrue() >= HIGHLIGHT_LIMIT) {
			throw new HighlightLimitExceededException(HIGHLIGHT_LIMIT);
		}
	}

	private static void requireSortable(Sort sort) {
		for (Sort.Order order : sort) {
			if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
				throw new UnknownSortPropertyException(order.getProperty());
			}
		}
	}

	private Car findOrThrow(UUID id) {
		return carRepository
				.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Carro nao encontrado: " + id));
	}

	/**
	 * Looks a partner up with {@code findById}, never {@code getReferenceById}: a reference to an id
	 * that does not exist only fails at flush, as a foreign key violation (409), instead of the 404 a
	 * caller-supplied id deserves.
	 */
	private Partner requirePartner(UUID partnerId) {
		return partnerRepository
				.findById(partnerId)
				.orElseThrow(() -> new ResourceNotFoundException("Parceiro nao encontrado: " + partnerId));
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static void addImages(Car car, List<String> urls, List<String> thumbnails) {
		List<String> effectiveUrls = urls != null ? urls : List.of();
		List<String> effectiveThumbnails = thumbnails != null ? thumbnails : List.of();
		for (int position = 0; position < effectiveUrls.size(); position++) {
			car.addImage(CarImage.builder()
					.url(effectiveUrls.get(position))
					.thumbnailUrl(position < effectiveThumbnails.size() ? effectiveThumbnails.get(position) : null)
					.position(position)
					.build());
		}
	}
}
