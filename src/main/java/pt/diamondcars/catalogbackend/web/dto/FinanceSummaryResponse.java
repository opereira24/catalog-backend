package pt.diamondcars.catalogbackend.web.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Response payload of {@code GET /api/backoffice/finances/summary} (TASK-005, AC A.3/B.3), ported
 * from {@code dcbo-backend}: the totals {@code dcbo/src/pages/finances.js} computes today by loading
 * every transaction into the browser, computed in the database instead.
 *
 * <p>{@link #totalReceitas} sums {@code receita} and {@code venda}; {@link #totalDespesas} sums
 * {@code despesa} and {@code compra}: the grouping of {@code calculateBalance} in {@code
 * dcbo/src/App.js:196-200}. Every field is a {@link BigDecimal} with scale 2, so Jackson writes
 * exactly 2 decimal places ({@code 0.00}, {@code -50.00}, {@code 1750.24}).
 *
 * @param totalReceitas sum of every {@code receita}/{@code venda} {@code valor} in the period
 * @param totalDespesas sum of every {@code despesa}/{@code compra} {@code valor} in the period
 * @param saldo {@code totalReceitas - totalDespesas}, possibly negative
 */
public record FinanceSummaryResponse(BigDecimal totalReceitas, BigDecimal totalDespesas, BigDecimal saldo) {

	/**
	 * Builds the summary from the two sums, deriving {@link #saldo} and scaling every field to 2
	 * decimal places with {@link RoundingMode#HALF_UP}.
	 *
	 * @param totalReceitas sum of every {@code receita}/{@code venda} {@code valor}
	 * @param totalDespesas sum of every {@code despesa}/{@code compra} {@code valor}
	 * @return the summary
	 */
	public static FinanceSummaryResponse of(BigDecimal totalReceitas, BigDecimal totalDespesas) {
		BigDecimal scaledReceitas = totalReceitas.setScale(2, RoundingMode.HALF_UP);
		BigDecimal scaledDespesas = totalDespesas.setScale(2, RoundingMode.HALF_UP);
		BigDecimal saldo = scaledReceitas.subtract(scaledDespesas).setScale(2, RoundingMode.HALF_UP);
		return new FinanceSummaryResponse(scaledReceitas, scaledDespesas, saldo);
	}
}
