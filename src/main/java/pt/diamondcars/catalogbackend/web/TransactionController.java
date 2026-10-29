package pt.diamondcars.catalogbackend.web;

import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
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
import pt.diamondcars.catalogbackend.domain.transaction.TransactionType;
import pt.diamondcars.catalogbackend.service.TransactionService;
import pt.diamondcars.catalogbackend.web.dto.TransactionRequest;
import pt.diamondcars.catalogbackend.web.dto.TransactionResponse;

/**
 * Back-office transaction ledger (TASK-005, AC A.1): list (filtered), get, create, update and
 * delete under {@code /api/backoffice/transactions} ({@link BackOfficeApi#PREFIX}). Ported from
 * {@code dcbo-backend}'s {@code TransactionController}; no public twin, so no {@code BackOffice}
 * prefix in the name.
 *
 * <p>Every endpoint needs a JWT and the {@code ADMIN} or {@code USER} role. Both roles may edit and
 * delete system-generated transactions (parity with {@code dcbo}; the requirement does not restrict
 * by role).
 */
@RestController
@RequestMapping(BackOfficeApi.PREFIX + "/transactions")
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class TransactionController {

	private final TransactionService transactionService;

	/**
	 * Creates the controller with its backing service.
	 *
	 * @param transactionService the service implementing every operation below
	 */
	public TransactionController(TransactionService transactionService) {
		this.transactionService = transactionService;
	}

	/**
	 * Binds {@code ?tipo=} by its lower-case stored value only; see {@link
	 * TransactionTypeQueryConverter} for why an editor and not a converter bean.
	 *
	 * @param binder the binder of the current request
	 */
	@InitBinder
	void bindTipoByItsStoredValue(WebDataBinder binder) {
		binder.registerCustomEditor(TransactionType.class, new TransactionTypeQueryConverter());
	}

	/**
	 * Lists transactions, newest {@code data} first by default.
	 *
	 * @param tipo when given, only this kind ({@code compra}, {@code venda}, {@code receita}, {@code
	 *     despesa}); anything else is a 400
	 * @param carroId when given, only the transactions of this car (an unknown car gives an empty
	 *     page)
	 * @param from when given, excludes transactions dated before it ({@code YYYY-MM-DD}, inclusive)
	 * @param to when given, excludes transactions dated after it ({@code YYYY-MM-DD}, inclusive)
	 * @param pageable pagination/sorting; defaults to 50 per page sorted by {@code data}, then
	 *     {@code createdAt}, then {@code id}, all descending (several transactions on the same day
	 *     are the normal case; without the tie-breakers pages repeat or skip rows); {@code sort} only
	 *     accepts {@code TransactionService#SORTABLE_PROPERTIES}
	 * @return the requested page, in a {@link PagedModel}
	 */
	@GetMapping
	public PagedModel<TransactionResponse> list(
			@RequestParam(required = false) TransactionType tipo,
			@RequestParam(required = false) UUID carroId,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@PageableDefault(size = 50, sort = {"data", "createdAt", "id"}, direction = Sort.Direction.DESC)
					Pageable pageable) {
		return new PagedModel<>(transactionService.list(tipo, carroId, from, to, pageable));
	}

	/**
	 * Fetches a single transaction.
	 *
	 * @param id the transaction's id
	 * @return the matching transaction (200), or a 404 {@code ApiError}
	 */
	@GetMapping("/{id}")
	public TransactionResponse get(@PathVariable UUID id) {
		return transactionService.get(id);
	}

	/**
	 * Creates a manual transaction.
	 *
	 * @param request the validated payload
	 * @return 201 with a {@code Location} header and the created transaction
	 */
	@PostMapping
	public ResponseEntity<TransactionResponse> create(@Valid @RequestBody TransactionRequest request) {
		TransactionResponse created = transactionService.create(request);
		return ResponseEntity.created(URI.create(BackOfficeApi.PREFIX + "/transactions/" + created.id()))
				.body(created);
	}

	/**
	 * Updates the 5 business fields of a transaction.
	 *
	 * @param id the transaction's id
	 * @param request the validated payload ({@code carroId} ignored)
	 * @return the updated transaction (200), or a 404 {@code ApiError}
	 */
	@PutMapping("/{id}")
	public TransactionResponse update(@PathVariable UUID id, @Valid @RequestBody TransactionRequest request) {
		return transactionService.update(id, request);
	}

	/**
	 * Deletes a transaction.
	 *
	 * @param id the transaction's id
	 */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		transactionService.delete(id);
	}
}
