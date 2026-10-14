package pt.diamondcars.catalogbackend.service;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.lead.LeadStatus;

/**
 * Builds the {@link Specification} of the {@code GET /api/backoffice/leads} filters (TASK-006, AC
 * E.2), so {@code status} and {@code carroId} combine without one derived query per combination.
 * Ported from {@code dcbo-backend}'s {@code LeadSpecifications}.
 *
 * <p>No predicate beyond the filters asked for (AC D.2): a lead the site saved before V2, never
 * forwarded, is listed like any other.
 */
final class BackOfficeLeadSpecifications {

	private BackOfficeLeadSpecifications() {}

	/**
	 * Matches every lead whose {@code status} and {@code car_id} equal the non-{@code null}
	 * arguments. A {@code null} argument does not filter (it never means "match nothing").
	 *
	 * @param status required status, or {@code null}
	 * @param carId required {@code car_id}, or {@code null}
	 * @return the combined specification; matches every lead when both arguments are {@code null}
	 */
	static Specification<Lead> matching(LeadStatus status, UUID carId) {
		return (root, query, criteriaBuilder) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (status != null) {
				predicates.add(criteriaBuilder.equal(root.get("status"), status));
			}
			if (carId != null) {
				predicates.add(criteriaBuilder.equal(root.get("car").get("id"), carId));
			}
			return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
		};
	}
}
