package pt.diamondcars.catalogbackend.web.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import pt.diamondcars.catalogbackend.web.exception.InvalidTransactionDateException;

/**
 * Request body of {@code POST /api/backoffice/transactions} and {@code PUT
 * /api/backoffice/transactions/{id}} (TASK-005, AC B.1), ported from {@code dcbo-backend}. Covers
 * the 4 {@code tipo} values {@code dcbo} writes today ({@code compra}, {@code venda}, {@code
 * receita}, {@code despesa}).
 *
 * <p>Not applied identically by both endpoints: {@code PUT} ignores {@link #carroId}. {@code
 * dcbo/src/components/transactions-form.js}, the only manual editor, never sends it, and the
 * "Editar" button of {@code dcbo/src/pages/finances.js} opens that same form for every transaction,
 * system-generated sale ones included; re-resolving {@link #carroId} on update would silently
 * detach a sale transaction from its car the first time it is edited.
 *
 * <p>Keys this record does not declare are ignored (Boot's default): {@code systemGenerated}, {@code
 * clienteId}, {@code partnerId} and {@code id} in a body have no effect. A security guarantee, not a
 * detail: a caller able to store {@code systemGenerated: true} would create a transaction that
 * reverting a car sale (TASK-010) deletes. Only {@code TransactionService#createSaleTransaction}
 * writes those fields.
 *
 * <p>Differences from {@code dcbo-backend}, each measured by the arquiteto (TASK-005 AC B.1):
 *
 * <ul>
 *   <li>{@code @Digits(integer = 8, fraction = 2)} on {@link #valor}: {@code NUMERIC(12,2)} rounds
 *       {@code 10.005} to {@code 10.01} without error, but the {@code 201} echoed {@code 10.005}
 *       (the entity in memory is not re-read), so the response lied about what was stored. The
 *       {@code dcbo} form has {@code step="0.01"}.
 *   <li>{@code @Size(max = 64)} on {@link #data}: the error message repeats the value, and without a
 *       ceiling a 1 MB {@code data} came back whole in the {@code ApiError}.
 *   <li>{@link #resolveData()} refuses dates outside {@code [}{@link #MIN_DATA}{@code , }{@link
 *       #MAX_DATA}{@code ]}: {@code LocalDate.parse} accepts {@code +10000-01-01} and {@code
 *       0000-12-31} (JDK 21); stored, they fell outside the sentinel dates of the finances summary
 *       and the "all time" totals silently stopped counting them.
 * </ul>
 *
 * @param tipo required, one of {@code compra}, {@code venda}, {@code receita}, {@code despesa}
 * @param valor required, strictly positive (the direction of the money comes only from {@link
 *     #tipo}), at most {@value CarRequest#PRICE_MAX_VALUE}, at most 2 decimal places
 * @param descricao required, at most 500 characters (the column size)
 * @param categoria optional free-text tag, at most 100 characters; no {@link Pattern} because {@code
 *     dcbo} only suggests values through a {@code datalist}
 * @param data required, at most 64 characters: a plain {@code "YYYY-MM-DD"} or a full ISO-8601
 *     instant ({@code dcbo/src/App.js:231} sends one as a fallback); see {@link #resolveData()}
 * @param carroId car the transaction is about, optional; on {@code POST} an id that does not exist
 *     is a 400; ignored by {@code PUT} (see above)
 */
public record TransactionRequest(
		@NotBlank @Pattern(regexp = TransactionRequest.TIPO_REGEXP) String tipo,
		@NotNull
		@DecimalMin(value = "0", inclusive = false)
		@DecimalMax(CarRequest.PRICE_MAX_VALUE)
		@Digits(integer = 8, fraction = 2)
		BigDecimal valor,
		@NotBlank @Size(max = 500) String descricao,
		@Size(max = 100) String categoria,
		@NotBlank @Size(max = 64) String data,
		UUID carroId) {

	/**
	 * Zone an ISO-8601 instant in {@link #data} is converted in to become a calendar date, and the
	 * zone of the date of a system-generated sale ({@code TransactionService#createSaleTransaction}):
	 * always {@code Europe/Lisbon}, never the JVM or driver default.
	 */
	public static final ZoneId TRANSACTION_ZONE = ZoneId.of("Europe/Lisbon");

	/** Earliest {@code data} accepted; also the lower bound the finances summary uses for "all time". */
	public static final LocalDate MIN_DATA = LocalDate.of(1, 1, 1);

	/** Latest {@code data} accepted; also the upper bound the finances summary uses for "all time". */
	public static final LocalDate MAX_DATA = LocalDate.of(9999, 12, 31);

	/** The 4 values {@code dcbo} writes to {@code transactions.tipo}. */
	static final String TIPO_REGEXP = "^(compra|venda|receita|despesa)$";

	/**
	 * Resolves {@link #data} into the calendar date {@code transactions.data} stores: a plain {@code
	 * "YYYY-MM-DD"} as-is; a full ISO-8601 instant converted to its calendar date in {@link
	 * #TRANSACTION_ZONE} (so {@code 2026-03-31T23:30:00Z}, already the next day in Lisbon under
	 * summer time, is {@code 2026-04-01}, not one day early).
	 *
	 * @return the resolved calendar date, within {@code [}{@link #MIN_DATA}{@code , }{@link
	 *     #MAX_DATA}{@code ]}
	 * @throws InvalidTransactionDateException if {@link #data} matches neither format or resolves
	 *     outside that range (mapped to 400)
	 */
	public LocalDate resolveData() {
		LocalDate resolved = parseData();
		if (resolved.isBefore(MIN_DATA) || resolved.isAfter(MAX_DATA)) {
			throw new InvalidTransactionDateException(data);
		}
		return resolved;
	}

	private LocalDate parseData() {
		try {
			return LocalDate.parse(data);
		} catch (DateTimeParseException plainDateFailure) {
			try {
				return Instant.parse(data).atZone(TRANSACTION_ZONE).toLocalDate();
			} catch (DateTimeParseException instantFailure) {
				throw new InvalidTransactionDateException(data);
			}
		}
	}
}
