package pt.diamondcars.catalogbackend.service;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.web.dto.CarResponse;
import pt.diamondcars.catalogbackend.web.dto.PagedResponse;
import pt.diamondcars.catalogbackend.web.exception.CarNotFoundException;

/**
 * Read-only query service backing {@code PublicCarController} (TASK-014): every method here maps
 * directly to one of the three public, unauthenticated endpoints {@code GET /api/cars}, {@code
 * GET /api/cars/{id}} and {@code GET /api/cars/highlights} the requirements list, translating
 * {@link Car} entities into {@link CarResponse} DTOs so the JPA model never leaks through the API
 * (requirement 4).
 */
@Service
@Transactional(readOnly = true)
public class CarQueryService {

	/**
	 * Maximum number of cars a single page of {@code GET /api/cars} may return, regardless of the
	 * {@code size} query parameter the caller asks for (requirement 1 / acceptance criterion:
	 * {@code ?size=500} is capped to this value).
	 */
	static final int MAX_PAGE_SIZE = 60;

	/**
	 * Default page size for {@code GET /api/cars} when the caller does not supply {@code size}.
	 */
	static final int DEFAULT_PAGE_SIZE = 12;

	/**
	 * Maximum number of cars {@code GET /api/cars/highlights} may ever return (requirement 3).
	 */
	static final int MAX_HIGHLIGHTS = 8;

	/**
	 * Fixed default ordering for {@code GET /api/cars}: available first, then reserved, then sold
	 * (via a raw {@code CASE} expression — {@link JpaSort#unsafe} lets an ordinary property-based
	 * {@link Sort} carry an arbitrary JPQL order-by expression), and within each group, most
	 * recently created first, with {@code id} ascending as a final tiebreaker. Spring Data only
	 * applies {@link Sort} to the content query of a {@code findAll(Specification, Pageable)} call,
	 * never to its count query, so this never breaks pagination totals.
	 *
	 * <p>The {@code id} tiebreaker is not cosmetic: without it, this is not a total order — rows
	 * that share the same status rank <em>and</em> the same {@code createdAt} (realistic after a
	 * bulk sync/backfill inserted in one transaction, since {@code created_at} defaults to the
	 * transaction start) can come back from Postgres in a different relative order between two
	 * queries with different {@code OFFSET}s, which duplicates or omits cars while paginating (fixed
	 * per TASK-014 review r1, IMPORTANTE 1). {@code id} is unique and immutable, so adding it last
	 * makes the ordering deterministic regardless of how many rows tie on the first two keys.
	 */
	private static final Sort DEFAULT_SORT =
			JpaSort.unsafe(
							Sort.Direction.ASC,
							"(CASE WHEN vendido = true THEN 2 WHEN reservado = true THEN 1 ELSE 0 END)")
					.and(Sort.by(Sort.Direction.DESC, "createdAt"))
					.and(Sort.by(Sort.Direction.ASC, "id"));

	private final CarRepository carRepository;

	/**
	 * Creates the service with its backing repository.
	 *
	 * @param carRepository the repository used for every query below
	 */
	public CarQueryService(CarRepository carRepository) {
		this.carRepository = carRepository;
	}

	/**
	 * Lists cars matching the given filters, paginated and ordered disponível → reservado →
	 * vendido, then by creation date descending within each group (requirement 1).
	 *
	 * @param criteria the optional filters to apply
	 * @param page zero-based page index requested by the caller
	 * @param size page size requested by the caller; capped to {@link #MAX_PAGE_SIZE} and defaulted
	 *     to {@link #DEFAULT_PAGE_SIZE} when {@code null}
	 * @return the requested page, mapped to {@link CarResponse}
	 */
	public PagedResponse<CarResponse> list(CarSearchCriteria criteria, int page, Integer size) {
		int effectiveSize = resolvePageSize(size);
		Pageable pageable = PageRequest.of(page, effectiveSize, DEFAULT_SORT);
		Page<Car> result = carRepository.findAll(CarSpecifications.matching(criteria), pageable);
		return PagedResponse.from(result.map(CarResponse::from));
	}

	/**
	 * Fetches a single car by id, regardless of its {@code vendido}/{@code reservado} state — the
	 * detail page shows sold/reserved cars too (with a watermark), only the listing hides them by
	 * default.
	 *
	 * @param id the car's id
	 * @return the matching car
	 * @throws CarNotFoundException if no car with this id exists (mapped to 404 by {@code
	 *     ApiExceptionHandler})
	 */
	public CarResponse get(UUID id) {
		return carRepository.findById(id).map(CarResponse::from).orElseThrow(() -> new CarNotFoundException(id));
	}

	/**
	 * Lists the highlighted, unsold cars shown on the site's homepage carousel, most recently
	 * created first with {@code id} ascending as a tiebreaker, never more than {@link
	 * #MAX_HIGHLIGHTS} (requirement 3).
	 *
	 * @return at most {@link #MAX_HIGHLIGHTS} highlighted, unsold cars
	 */
	public List<CarResponse> highlights() {
		return carRepository
				.findByDestaqueTrueAndVendidoFalseOrderByCreatedAtDescIdAsc(PageRequest.of(0, MAX_HIGHLIGHTS))
				.stream()
				.map(CarResponse::from)
				.toList();
	}

	/**
	 * Resolves the effective page size for {@code GET /api/cars}: {@link #DEFAULT_PAGE_SIZE} when
	 * the caller did not supply one, otherwise the requested size capped to {@link #MAX_PAGE_SIZE}
	 * (never less than 1, so a caller-supplied {@code 0} or negative value cannot make Spring Data
	 * reject the {@link Pageable}).
	 *
	 * @param requestedSize the caller-supplied {@code size} query parameter, or {@code null}
	 * @return the page size to actually use
	 */
	private static int resolvePageSize(Integer requestedSize) {
		if (requestedSize == null) {
			return DEFAULT_PAGE_SIZE;
		}
		return Math.max(1, Math.min(requestedSize, MAX_PAGE_SIZE));
	}
}
