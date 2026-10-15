package pt.diamondcars.catalogbackend.service;

import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import pt.diamondcars.catalogbackend.domain.transaction.Transaction;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionType;

/**
 * Builds the {@link Specification} of the {@code GET /api/backoffice/transactions} filters
 * (TASK-005, AC C.1), so any subset of {@code tipo}/{@code carroId}/{@code from}/{@code to} combines
 * without one derived query per combination. Ported from {@code dcbo-backend}.
 */
final class TransactionSpecifications {

	private TransactionSpecifications() {}

	/**
	 * Matches every transaction whose {@code tipo} and {@code car_id} equal the non-{@code null}
	 * arguments and whose {@code data} is within {@code [from, to]}, each bound applied only when
	 * non-{@code null}. A {@code null} argument does not filter (it never means "match nothing").
	 *
	 * @param tipo required {@code tipo}, or {@code null}
	 * @param carId required {@code car_id}, or {@code null}
	 * @param from inclusive lower bound of {@code data}, or {@code null}
	 * @param to inclusive upper bound of {@code data}, or {@code null}
	 * @return the combined specification; matches everything when every argument is {@code null}
	 */
	static Specification<Transaction> matching(TransactionType tipo, UUID carId, LocalDate from, LocalDate to) {
		return (root, query, criteriaBuilder) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (tipo != null) {
				predicates.add(criteriaBuilder.equal(root.get("tipo"), tipo));
			}
			if (carId != null) {
				predicates.add(criteriaBuilder.equal(root.get("car").get("id"), carId));
			}
			if (from != null) {
				predicates.add(criteriaBuilder.greaterThanOrEqualTo(root.get("data"), from));
			}
			if (to != null) {
				predicates.add(criteriaBuilder.lessThanOrEqualTo(root.get("data"), to));
			}
			return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
		};
	}
}
