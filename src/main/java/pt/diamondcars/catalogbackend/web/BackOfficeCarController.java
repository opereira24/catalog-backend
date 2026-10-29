package pt.diamondcars.catalogbackend.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pt.diamondcars.catalogbackend.service.BackOfficeCarService;
import pt.diamondcars.catalogbackend.web.dto.BackOfficeCarResponse;
import pt.diamondcars.catalogbackend.web.dto.CarRequest;
import pt.diamondcars.catalogbackend.web.dto.HighlightRequest;
import pt.diamondcars.catalogbackend.web.dto.SellCarRequest;

/**
 * Back-office car API under {@code /api/backoffice/cars} ({@link BackOfficeApi#PREFIX}): list, get,
 * create, reserve, release a reservation and feature (TASK-003); update, delete, sell and revert a
 * sale (TASK-010). Ported from {@code dcbo-backend}'s {@code CarController}. The public {@link
 * PublicCarController} on {@code /api/cars} is a different contract and stays untouched.
 *
 * <p>Every endpoint requires a JWT (the {@code SecurityConfig} default for anything outside {@code
 * PublicEndpoints}) <b>and</b> the {@code ADMIN} or {@code USER} role. "Authenticated" alone is not
 * enough: a valid token of the tenant without any role (a sign-up in the SPA, or a user the Action
 * has not given a role yet) would create cars and read purchase prices. A method-level
 * {@code @PreAuthorize} (TASK-010's {@code DELETE}, {@code ADMIN} only) overrides this one.
 */
@RestController
@RequestMapping(BackOfficeApi.PREFIX + "/cars")
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class BackOfficeCarController {

	private final BackOfficeCarService carService;

	/**
	 * Creates the controller with its service.
	 *
	 * @param carService the service implementing every operation below
	 */
	public BackOfficeCarController(BackOfficeCarService carService) {
		this.carService = carService;
	}

	/**
	 * Lists cars, most recently created first by default ({@code id} breaks ties, so pages are
	 * stable), optionally filtered.
	 *
	 * @param vendido when given, only cars with this {@code vendido}
	 * @param reservado when given, only cars with this {@code reservado}
	 * @param destaque when given, only cars with this {@code destaque}
	 * @param pageable 50 per page by default, at most 100; {@code sort} only accepts the scalar
	 *     properties of {@code BackOfficeCarService#SORTABLE_PROPERTIES} (anything else is 400)
	 * @return the page, as a {@link PagedModel} ({@code content} + {@code page}), a JSON shape Spring
	 *     Data keeps stable across versions
	 */
	@GetMapping
	public PagedModel<BackOfficeCarResponse> list(
			@RequestParam(required = false) Boolean vendido,
			@RequestParam(required = false) Boolean reservado,
			@RequestParam(required = false) Boolean destaque,
			@PageableDefault(size = 50, sort = {"createdAt", "id"}, direction = Sort.Direction.DESC)
					Pageable pageable) {
		return new PagedModel<>(carService.list(vendido, reservado, destaque, pageable));
	}

	/**
	 * Fetches a car.
	 *
	 * @param id the car's id
	 * @return the car (200), or 404 if it does not exist
	 */
	@GetMapping("/{id}")
	public BackOfficeCarResponse get(@PathVariable UUID id) {
		return carService.get(id);
	}

	/**
	 * Creates a car.
	 *
	 * @param request the validated body
	 * @return 201 with {@code Location: /api/backoffice/cars/<id>} and the created car
	 */
	@PostMapping
	public ResponseEntity<BackOfficeCarResponse> create(@Valid @RequestBody CarRequest request) {
		BackOfficeCarResponse created = carService.create(request);
		return ResponseEntity.created(URI.create(BackOfficeApi.PREFIX + "/cars/" + created.id())).body(created);
	}

	/**
	 * Reserves a car (idempotent).
	 *
	 * @param id the car's id
	 * @return the car
	 */
	@PostMapping("/{id}/reserve")
	public BackOfficeCarResponse reserve(@PathVariable UUID id) {
		return carService.reserve(id);
	}

	/**
	 * Releases a car's reservation (idempotent).
	 *
	 * @param id the car's id
	 * @return the car
	 */
	@PostMapping("/{id}/release-reservation")
	public BackOfficeCarResponse releaseReservation(@PathVariable UUID id) {
		return carService.releaseReservation(id);
	}

	/**
	 * Features a car or removes it from the featured set, with the 8-car limit enforced on the
	 * server.
	 *
	 * @param id the car's id
	 * @param request the requested state
	 * @return the car (200), or 409 if featuring it would exceed the limit
	 */
	@PatchMapping("/{id}/highlight")
	public BackOfficeCarResponse highlight(@PathVariable UUID id, @Valid @RequestBody HighlightRequest request) {
		return carService.highlight(id, request);
	}

	/**
	 * Replaces a car's data (TASK-010). Absent {@code observacoes}, {@code destaque} and photos are
	 * kept (null semantics in {@link CarRequest}); a sold consignment car's partner counters follow
	 * the edit.
	 *
	 * @param id the car's id
	 * @param request the validated body
	 * @return the car (200); 404 if the car or the partner does not exist; 409 if featuring it would
	 *     exceed the limit
	 */
	@PutMapping("/{id}")
	public BackOfficeCarResponse update(@PathVariable UUID id, @Valid @RequestBody CarRequest request) {
		return carService.update(id, request);
	}

	/**
	 * Deletes a car (TASK-010), {@code ADMIN} only: this method-level annotation overrides the class
	 * one, so a {@code USER} gets 403.
	 *
	 * @param id the car's id
	 * @param deleteTransactions {@code true} by default: also delete the car's transactions and, if it
	 *     was sold, decrement its buyer's {@code purchases_count}; {@code false} keeps the transactions
	 *     without car. Not a boolean: 400 {@code Parametro 'deleteTransactions' com valor invalido}
	 */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize("hasRole('ADMIN')")
	public void delete(@PathVariable UUID id, @RequestParam(defaultValue = "true") boolean deleteTransactions) {
		carService.delete(id, deleteTransactions);
	}

	/**
	 * Sells a car (TASK-010): updates the buyer's and the consignment partner's counters and creates
	 * the system-generated sale transaction, all or nothing.
	 *
	 * @param id the car's id
	 * @param request the validated body (sale price, optional buyer)
	 * @return the sold car (200); 404 if the car or the client does not exist; 409 if already sold
	 */
	@PostMapping("/{id}/sell")
	public BackOfficeCarResponse sell(@PathVariable UUID id, @Valid @RequestBody SellCarRequest request) {
		return carService.sell(id, request);
	}

	/**
	 * Reverts a sale (TASK-010), undoing exactly what {@link #sell} registered. On a car that is not
	 * sold it is a no-op 200.
	 *
	 * @param id the car's id
	 * @return the car (200), or 404 if it does not exist
	 */
	@PostMapping("/{id}/revert-sale")
	public BackOfficeCarResponse revertSale(@PathVariable UUID id) {
		return carService.revertSale(id);
	}
}
