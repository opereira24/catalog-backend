package pt.diamondcars.catalogbackend.web;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pt.diamondcars.catalogbackend.service.LeadService;
import pt.diamondcars.catalogbackend.web.dto.LeadRequest;
import pt.diamondcars.catalogbackend.web.dto.LeadResponse;

/**
 * Public, unauthenticated REST API for sales-lead submission (TASK-015) — the endpoint the {@code
 * dc} site calls in place of the direct Firestore writes it performs today ({@code
 * dc/src/services/firebaseService.js:91,118}). Guarded only by {@link RateLimitInterceptor}
 * (requirement 4), never by authentication: any site visitor can submit a lead.
 */
@RestController
@RequestMapping("/api/leads")
public class PublicLeadController {

	private final LeadService leadService;

	/**
	 * Creates the controller with its backing service.
	 *
	 * @param leadService the service implementing lead creation and forwarding
	 */
	public PublicLeadController(LeadService leadService) {
		this.leadService = leadService;
	}

	/**
	 * Submits a new sales lead. Always persists it locally and returns 201, even when the
	 * best-effort forward to {@code dcbo-backend} (requirement 6) fails.
	 *
	 * @param request the validated payload
	 * @return 201 with the created lead's id
	 */
	@PostMapping
	public ResponseEntity<LeadResponse> create(@Valid @RequestBody LeadRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED).body(leadService.createFromWebsite(request));
	}
}
