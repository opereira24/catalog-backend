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
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pt.diamondcars.catalogbackend.domain.lead.LeadStatus;
import pt.diamondcars.catalogbackend.service.BackOfficeLeadService;
import pt.diamondcars.catalogbackend.web.dto.BackOfficeLeadRequest;
import pt.diamondcars.catalogbackend.web.dto.BackOfficeLeadResponse;

/**
 * Back-office lead pipeline (TASK-006, AC E.2): list (filtered), get, create, replace and delete
 * under {@code /api/backoffice/leads} ({@link BackOfficeApi#PREFIX}). Ported from {@code
 * dcbo-backend}'s {@code LeadController}, same verbs and bodies. {@code BackOffice} prefix because
 * the public twin {@link PublicLeadController} owns {@code POST /api/leads}, which stays the only
 * public lead route.
 *
 * <p>{@code PUT}, not {@code PATCH}: {@code updateLead} of {@code dcbo} always sends the 6 editable
 * fields, so the body is a replacement. Every endpoint needs a JWT and the {@code ADMIN} or {@code
 * USER} role (parity: in {@code dcbo-backend} any authenticated user does everything, delete
 * included).
 */
@RestController
@RequestMapping(BackOfficeApi.PREFIX + "/leads")
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class BackOfficeLeadController {

	private final BackOfficeLeadService leadService;

	/**
	 * Creates the controller with its backing service.
	 *
	 * @param leadService the service implementing every operation below
	 */
	public BackOfficeLeadController(BackOfficeLeadService leadService) {
		this.leadService = leadService;
	}

	/**
	 * Binds {@code ?status=} by its stored lower-case value only; see {@link
	 * LeadStatusQueryConverter} for why an editor and not a converter bean.
	 *
	 * @param binder the binder of the current request
	 */
	@InitBinder
	void bindStatusByItsStoredValue(WebDataBinder binder) {
		binder.registerCustomEditor(LeadStatus.class, new LeadStatusQueryConverter());
	}

	/**
	 * Lists leads, most recently created first by default.
	 *
	 * @param status when given, only leads with this stored status ({@code ativo}, ...); anything
	 *     else is a 400; empty means no filter
	 * @param carroId when given, only the leads of this car; empty means no filter
	 * @param pageable pagination/sorting; defaults to 50 per page by {@code createdAt} DESC, then
	 *     {@code id} DESC; {@code sort} only accepts {@code BackOfficeLeadService#SORTABLE_PROPERTIES}
	 * @return the requested page, in a {@link PagedModel}
	 */
	@GetMapping
	public PagedModel<BackOfficeLeadResponse> list(
			@RequestParam(required = false) LeadStatus status,
			@RequestParam(required = false) UUID carroId,
			@PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
		return new PagedModel<>(leadService.list(status, carroId, pageable));
	}

	/**
	 * Fetches a single lead.
	 *
	 * @param id the lead's id
	 * @return the matching lead (200), or a 404 {@code ApiError}
	 */
	@GetMapping("/{id}")
	public BackOfficeLeadResponse get(@PathVariable UUID id) {
		return leadService.get(id);
	}

	/**
	 * Creates a lead in the back-office ({@code origem = backoffice}).
	 *
	 * @param request the validated payload
	 * @return 201 with a {@code Location} header and the created lead
	 */
	@PostMapping
	public ResponseEntity<BackOfficeLeadResponse> create(@Valid @RequestBody BackOfficeLeadRequest request) {
		BackOfficeLeadResponse created = leadService.create(request);
		return ResponseEntity.created(URI.create(BackOfficeApi.PREFIX + "/leads/" + created.id())).body(created);
	}

	/**
	 * Replaces the 6 editable fields of a lead.
	 *
	 * @param id the lead's id
	 * @param request the validated payload (car fields ignored)
	 * @return the updated lead (200), or a 404 {@code ApiError}
	 */
	@PutMapping("/{id}")
	public BackOfficeLeadResponse update(@PathVariable UUID id, @Valid @RequestBody BackOfficeLeadRequest request) {
		return leadService.update(id, request);
	}

	/**
	 * Deletes a lead and its notifications.
	 *
	 * @param id the lead's id
	 */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		leadService.delete(id);
	}
}
