package pt.diamondcars.catalogbackend.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.diamondcars.catalogbackend.service.CarQueryService;
import pt.diamondcars.catalogbackend.service.CarSearchCriteria;
import pt.diamondcars.catalogbackend.web.dto.CarResponse;
import pt.diamondcars.catalogbackend.web.dto.PagedResponse;

/**
 * Public, unauthenticated REST API for the car catalog (TASK-014) — the endpoints the {@code dc}
 * site consumes in place of the Firestore reads it performs today ({@code
 * dc/src/services/firebaseService.js:43,72}). None of these endpoints require authentication;
 * writes to the catalog only ever happen through the internal sync endpoints (TASK-016), never
 * through this controller.
 */
@RestController
@RequestMapping("/api/cars")
public class PublicCarController {

	private final CarQueryService carQueryService;

	/**
	 * Creates the controller with its backing service.
	 *
	 * @param carQueryService the service implementing every operation below
	 */
	public PublicCarController(CarQueryService carQueryService) {
		this.carQueryService = carQueryService;
	}

	/**
	 * Lists cars, paginated and filtered, ordered disponível → reservado → vendido and, within each
	 * group, most recently created first (requirement 1).
	 *
	 * @param page zero-based page index, defaults to 0; out-of-range values (negative, or large
	 *     enough to overflow the internal offset computation) are clamped instead of failing (see
	 *     {@code CarQueryService#resolvePage})
	 * @param size page size, defaults to 12, capped to 60
	 * @param marca exact brand filter, optional
	 * @param combustivel exact fuel-type filter, optional
	 * @param precoMin inclusive lower price bound, optional
	 * @param precoMax inclusive upper price bound, optional
	 * @param anoMin inclusive lower model-year bound, optional
	 * @param anoMax inclusive upper model-year bound, optional
	 * @param destaque exact featured-flag filter, optional
	 * @param incluirVendidos whether to include sold cars, defaults to {@code false}
	 * @return the requested page of cars
	 */
	@GetMapping
	public PagedResponse<CarResponse> list(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(required = false) Integer size,
			@RequestParam(required = false) String marca,
			@RequestParam(required = false) String combustivel,
			@RequestParam(required = false) BigDecimal precoMin,
			@RequestParam(required = false) BigDecimal precoMax,
			@RequestParam(required = false) Integer anoMin,
			@RequestParam(required = false) Integer anoMax,
			@RequestParam(required = false) Boolean destaque,
			@RequestParam(defaultValue = "false") boolean incluirVendidos) {
		CarSearchCriteria criteria =
				new CarSearchCriteria(
						marca, combustivel, precoMin, precoMax, anoMin, anoMax, destaque, incluirVendidos);
		return carQueryService.list(criteria, page, size);
	}

	/**
	 * Fetches a single car.
	 *
	 * @param id the car's id
	 * @return the matching car (200), or a 404 {@link pt.diamondcars.catalogbackend.web.dto.ApiError}
	 *     if it does not exist
	 */
	@GetMapping("/{id}")
	public CarResponse get(@PathVariable UUID id) {
		return carQueryService.get(id);
	}

	/**
	 * Lists the highlighted, unsold cars shown on the site's homepage carousel (requirement 3).
	 *
	 * @return at most 8 highlighted, unsold cars
	 */
	@GetMapping("/highlights")
	public List<CarResponse> highlights() {
		return carQueryService.highlights();
	}
}
