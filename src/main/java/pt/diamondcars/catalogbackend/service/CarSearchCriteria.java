package pt.diamondcars.catalogbackend.service;

import java.math.BigDecimal;

/**
 * The optional filters {@code GET /api/cars} accepts (TASK-014 requirement 1), gathered into one
 * value so {@code PublicCarController} has a single object to pass to {@link CarQueryService}
 * instead of eight loose parameters.
 *
 * @param marca exact brand match, or {@code null}/blank for no restriction
 * @param combustivel exact fuel-type match, or {@code null}/blank for no restriction
 * @param precoMin inclusive lower price bound, or {@code null} for no restriction
 * @param precoMax inclusive upper price bound, or {@code null} for no restriction
 * @param anoMin inclusive lower model-year bound, or {@code null} for no restriction
 * @param anoMax inclusive upper model-year bound, or {@code null} for no restriction
 * @param destaque exact featured-flag match, or {@code null} for no restriction
 * @param incluirVendidos whether sold cars are included in the result set; {@code false} by
 *     default (never {@code null} — the controller always resolves it before building this
 *     record)
 */
public record CarSearchCriteria(
		String marca,
		String combustivel,
		BigDecimal precoMin,
		BigDecimal precoMax,
		Integer anoMin,
		Integer anoMax,
		Boolean destaque,
		boolean incluirVendidos) {}
