package pt.diamondcars.catalogbackend.web;

import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pt.diamondcars.catalogbackend.service.TransactionService;
import pt.diamondcars.catalogbackend.web.dto.FinanceSummaryResponse;

/**
 * Back-office finance totals (TASK-005, AC A.1/A.3) under {@code /api/backoffice/finances}, ported
 * from {@code dcbo-backend}'s {@code FinanceController}: replaces the totals {@code
 * dcbo/src/pages/finances.js} computes in the browser by loading every transaction. Needs a JWT and
 * the {@code ADMIN} or {@code USER} role.
 */
@RestController
@RequestMapping(BackOfficeApi.PREFIX + "/finances")
@PreAuthorize("hasAnyRole('ADMIN', 'USER')")
public class FinanceController {

	private final TransactionService transactionService;

	/**
	 * Creates the controller with its backing service.
	 *
	 * @param transactionService the service computing the summary
	 */
	public FinanceController(TransactionService transactionService) {
		this.transactionService = transactionService;
	}

	/**
	 * Computes {@code totalReceitas}, {@code totalDespesas} and {@code saldo} for a period; without
	 * {@code from}/{@code to}, over the whole history. {@code from} after {@code to} is not an error
	 * (every total is {@code 0.00}).
	 *
	 * @param from when given, only counts transactions dated on or after it ({@code YYYY-MM-DD})
	 * @param to when given, only counts transactions dated on or before it ({@code YYYY-MM-DD})
	 * @return the summary
	 */
	@GetMapping("/summary")
	public FinanceSummaryResponse summary(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return transactionService.summary(from, to);
	}
}
