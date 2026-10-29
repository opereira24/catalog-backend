package pt.diamondcars.catalogbackend.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;

/**
 * {@link StableSort#withIdTiebreak} (TASK-013, B.5). Plain JUnit, no Spring and no database: the
 * controller tests prove the effect on the query, this one pins the rule itself (append {@code id}
 * DESC unless {@code id} is already there, keep page number and size).
 */
class StableSortTest {

	@Test
	void anUnsortedRequestIsOrderedByIdDescending() {
		Pageable stable = StableSort.withIdTiebreak(PageRequest.of(2, 25));

		assertThat(stable.getSort()).containsExactly(Order.desc("id"));
		assertThat(stable.getPageNumber()).isEqualTo(2);
		assertThat(stable.getPageSize()).isEqualTo(25);
	}

	@Test
	void idDescendingIsAppendedAfterTheRequestedOrder() {
		Pageable requested = PageRequest.of(3, 10, Sort.by(Order.asc("valor"), Order.desc("createdAt")));

		Pageable stable = StableSort.withIdTiebreak(requested);

		assertThat(stable.getSort()).containsExactly(Order.asc("valor"), Order.desc("createdAt"), Order.desc("id"));
		assertThat(stable.getPageNumber()).isEqualTo(3);
		assertThat(stable.getPageSize()).isEqualTo(10);
	}

	/**
	 * A sort that already has {@code id}, first or last and in either direction, is returned as is:
	 * no second {@code id} and the requested direction is kept.
	 *
	 * @param sort the requested {@code sort}, in the {@code property,direction;...} form of this test
	 */
	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"id,asc", "id,desc", "valor,asc;id,asc", "id,asc;valor,desc"})
	void aSortThatAlreadyHasIdIsKeptAsRequested(String sort) {
		Pageable requested = PageRequest.of(1, 5, parse(sort));

		assertThat(StableSort.withIdTiebreak(requested)).isSameAs(requested);
	}

	private static Sort parse(String sort) {
		Sort result = Sort.unsorted();
		for (String order : sort.split(";")) {
			String[] parts = order.split(",");
			result = result.and(Sort.by(Sort.Direction.fromString(parts[1]), parts[0]));
		}
		return result;
	}
}
