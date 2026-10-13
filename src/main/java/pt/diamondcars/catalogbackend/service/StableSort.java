package pt.diamondcars.catalogbackend.service;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Makes every paginated back-office listing end its {@code ORDER BY} in {@code id} DESC (TASK-013).
 *
 * <p>Why: a {@code ?sort=} sent by the client replaces the controller's {@code @PageableDefault}
 * entirely, so the {@code id} of the default (cars, transactions) is lost; and the default of
 * clients and partners ({@code createdAt} only) never had it. Rows that tie on every requested
 * property then come back in whatever order the PostgreSQL plan produces, and consecutive pages
 * repeat or skip rows. The primary key is unique, so a final {@code id} makes the order total.
 * {@code id} DESC matches the tie-breaker of the car and transaction defaults.
 */
final class StableSort {

	private static final String ID = "id";

	private StableSort() {}

	/**
	 * Appends {@code id} DESC to the sort of {@code pageable} unless it already orders by {@code id}
	 * (in any position and direction, which is then kept as requested).
	 *
	 * @param pageable a paged request, as resolved by the back-office controllers (their {@code
	 *     @PageableDefault} never yields an unpaged one)
	 * @return {@code pageable} itself when its sort already has {@code id}; otherwise a request for the
	 *     same page number and size whose sort is the original one followed by {@code id} DESC
	 */
	static Pageable withIdTiebreak(Pageable pageable) {
		Sort sort = pageable.getSort();
		if (sort.getOrderFor(ID) != null) {
			return pageable;
		}
		return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort.and(Sort.by(Sort.Order.desc(ID))));
	}
}
