package pt.diamondcars.catalogbackend.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pt.diamondcars.catalogbackend.service.ClientService;
import pt.diamondcars.catalogbackend.web.dto.BackOfficeCarResponse;
import pt.diamondcars.catalogbackend.web.dto.ClientRequest;
import pt.diamondcars.catalogbackend.web.dto.ClientResponse;

/**
 * Back-office client API (TASK-004): list (with search), get, create, update, delete, and list a
 * client's purchased cars, under {@code /api/backoffice/clients} ({@link BackOfficeApi#PREFIX}).
 * Ported from {@code dcbo-backend}'s {@code ClientController}; no public twin exists, so the name
 * keeps no {@code BackOffice} prefix (TASK-003 AC A.3).
 *
 * <p>Every endpoint requires a JWT and the {@code ADMIN} or {@code USER} role (same reasoning as
 * {@code BackOfficeCarController}); unlike {@code dcbo-backend}, where no role existed to check,
 * a valid token of the tenant without any role would otherwise manage the client list. No
 * endpoint is further restricted beyond the class-level rule — deleting a client is guarded
 * instead by {@link ClientService#delete(UUID)} refusing when cars still reference it.
 */
@RestController
@RequestMapping(BackOfficeApi.PREFIX + "/clients")
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class ClientController {

	private final ClientService clientService;

	/**
	 * Creates the controller with its backing service.
	 *
	 * @param clientService the service implementing every operation below
	 */
	public ClientController(ClientService clientService) {
		this.clientService = clientService;
	}

	/**
	 * Lists clients, most recently created first by default, optionally filtered by a free-text
	 * search over name/email/phone/NIF.
	 *
	 * @param search free text to search for, or omitted to not filter at all
	 * @param pageable pagination/sorting; defaults to 50 per page, sorted by {@code createdAt}
	 *     descending, when the caller does not ask for something else; an unknown {@code sort}
	 *     property responds 400 via the shared {@code PropertyReferenceException} handler
	 *     (TASK-003 AC H) — {@code Client} has no association to guard with its own allow-list
	 * @return the requested page of clients, wrapped in a {@link PagedModel}
	 */
	@GetMapping
	public PagedModel<ClientResponse> list(
			@RequestParam(required = false) String search,
			@PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return new PagedModel<>(clientService.list(search, pageable));
	}

	/**
	 * Fetches a single client.
	 *
	 * @param id the client's id
	 * @return the matching client (200), or a 404 {@code ApiError} if it does not exist
	 */
	@GetMapping("/{id}")
	public ClientResponse get(@PathVariable UUID id) {
		return clientService.get(id);
	}

	/**
	 * Creates a new client.
	 *
	 * @param request the validated payload
	 * @return 201, with a {@code Location} header pointing at the new client and the created client
	 *     as the body
	 */
	@PostMapping
	public ResponseEntity<ClientResponse> create(@Valid @RequestBody ClientRequest request) {
		ClientResponse created = clientService.create(request);
		return ResponseEntity.created(URI.create(BackOfficeApi.PREFIX + "/clients/" + created.id()))
				.body(created);
	}

	/**
	 * Updates an existing client, replacing every field with the given payload.
	 *
	 * @param id the client's id
	 * @param request the validated payload
	 * @return the updated client (200), or a 404 {@code ApiError} if it does not exist
	 */
	@PutMapping("/{id}")
	public ClientResponse update(@PathVariable UUID id, @Valid @RequestBody ClientRequest request) {
		return clientService.update(id, request);
	}

	/**
	 * Deletes a client.
	 *
	 * @param id the client's id
	 * @throws pt.diamondcars.catalogbackend.web.exception.ResourceInUseException if the client has
	 *     cars associated (mapped to 409 by {@link ApiExceptionHandler})
	 */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		clientService.delete(id);
	}

	/**
	 * Lists the cars a client has purchased, most recently created first — backs {@code
	 * dcbo/src/components/client-cars-modal.js}.
	 *
	 * @param id the client's id
	 * @return the matching cars (200), or a 404 {@code ApiError} if the client does not exist
	 */
	@GetMapping("/{id}/cars")
	public List<BackOfficeCarResponse> cars(@PathVariable UUID id) {
		return clientService.cars(id);
	}
}
