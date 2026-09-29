package pt.diamondcars.catalogbackend.service;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import pt.diamondcars.catalogbackend.domain.car.Car;

/**
 * Builds the {@link Specification} {@link CarQueryService} uses to combine every optional filter
 * {@code GET /api/cars} accepts (TASK-014 requirement 1: brand, fuel type, price/year range,
 * featured flag, whether to include sold cars), so any subset can be requested together without
 * one derived-query method per combination — the same pattern already used by {@code
 * dcbo-backend}'s equivalent class.
 */
final class CarSpecifications {

	private CarSpecifications() {}

	/**
	 * Builds a specification that matches every car satisfying all the given, independently
	 * optional filters. A {@code null} argument (or, for {@link CarSearchCriteria#marca()}/{@link
	 * CarSearchCriteria#combustivel()}, a blank one) is not filtered on at all.
	 *
	 * @param criteria the caller's search criteria; {@link CarSearchCriteria#incluirVendidos()} is
	 *     never {@code null} and, when {@code false}, adds a {@code vendido = false} predicate
	 * @return the combined specification
	 */
	static Specification<Car> matching(CarSearchCriteria criteria) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (!criteria.incluirVendidos()) {
				predicates.add(cb.isFalse(root.get("vendido")));
			}
			if (criteria.marca() != null && !criteria.marca().isBlank()) {
				predicates.add(cb.equal(root.get("marca"), criteria.marca()));
			}
			if (criteria.combustivel() != null && !criteria.combustivel().isBlank()) {
				predicates.add(cb.equal(root.get("combustivel"), criteria.combustivel()));
			}
			if (criteria.precoMin() != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("preco"), criteria.precoMin()));
			}
			if (criteria.precoMax() != null) {
				predicates.add(cb.lessThanOrEqualTo(root.get("preco"), criteria.precoMax()));
			}
			if (criteria.anoMin() != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("ano"), criteria.anoMin()));
			}
			if (criteria.anoMax() != null) {
				predicates.add(cb.lessThanOrEqualTo(root.get("ano"), criteria.anoMax()));
			}
			if (criteria.destaque() != null) {
				predicates.add(cb.equal(root.get("destaque"), criteria.destaque()));
			}
			return cb.and(predicates.toArray(new Predicate[0]));
		};
	}
}
