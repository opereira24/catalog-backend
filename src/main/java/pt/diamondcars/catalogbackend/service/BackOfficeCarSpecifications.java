package pt.diamondcars.catalogbackend.service;

import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;
import pt.diamondcars.catalogbackend.domain.car.Car;

/**
 * Builds the {@link Specification} behind the back-office car listing filters ({@code
 * GET /api/backoffice/cars?vendido=&reservado=&destaque=}, TASK-003, AC D.1), so any subset of the
 * three can be combined with AND without one derived-query method per combination. Ported from
 * {@code dcbo-backend}'s {@code CarSpecifications}; the public {@link CarSpecifications} (other
 * filters, never sold cars by default) is a different contract and stays untouched.
 */
final class BackOfficeCarSpecifications {

	private BackOfficeCarSpecifications() {}

	/**
	 * Matches every car whose {@code vendido}/{@code reservado}/{@code destaque} equals the
	 * corresponding non-{@code null} argument. A {@code null} argument is no filter at all (not
	 * "match false").
	 *
	 * @param vendido required value of {@code cars.vendido}, or {@code null}
	 * @param reservado required value of {@code cars.reservado}, or {@code null}
	 * @param destaque required value of {@code cars.destaque}, or {@code null}
	 * @return the combined specification; matches every car when all three are {@code null}
	 */
	static Specification<Car> matching(Boolean vendido, Boolean reservado, Boolean destaque) {
		return (root, query, criteriaBuilder) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (vendido != null) {
				predicates.add(criteriaBuilder.equal(root.get("vendido"), vendido));
			}
			if (reservado != null) {
				predicates.add(criteriaBuilder.equal(root.get("reservado"), reservado));
			}
			if (destaque != null) {
				predicates.add(criteriaBuilder.equal(root.get("destaque"), destaque));
			}
			return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
		};
	}
}
