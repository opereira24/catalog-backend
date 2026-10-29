package pt.diamondcars.catalogbackend.domain.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link Transaction}, ported unchanged from {@code dcbo-backend}
 * (TASK-001): {@link JpaSpecificationExecutor} for the combinable {@code tipo}/{@code
 * carroId}/{@code from}/{@code to} filters of the transactions listing, plus the lookup and
 * aggregate queries the car sale and finances flows use.
 */
public interface TransactionRepository
		extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

	/**
	 * Lists every transaction linked to a given car, equivalent to {@code deleteCarTransactions} in
	 * {@code dcbo/src/services/firebaseService.js:415} — used when deleting a car to remove every
	 * transaction of that car before the car itself is deleted (the
	 * {@code transactions.car_id ON DELETE SET NULL} foreign key alone would only detach them, never
	 * delete the rows).
	 *
	 * @param carId the {@link pt.diamondcars.catalogbackend.domain.car.Car} id to filter by
	 * @return all transactions whose {@code car_id} equals {@code carId}
	 */
	List<Transaction> findByCarId(UUID carId);

	/**
	 * Lists the system-generated transactions linked to a given car, equivalent to {@code
	 * deleteSaleTransaction} in {@code dcbo/src/services/firebaseService.js:427} — used when a sale
	 * is reverted to remove exactly the transaction the sale created for that car, never a manual
	 * transaction that happens to share the same {@code car_id}/{@code tipo}.
	 *
	 * @param carId the {@link pt.diamondcars.catalogbackend.domain.car.Car} id to filter by
	 * @return every system-generated transaction whose {@code car_id} equals {@code carId}
	 */
	List<Transaction> findByCarIdAndSystemGeneratedTrue(UUID carId);

	/**
	 * Sums {@code valor} across every transaction whose {@code tipo} is one of {@code tipos} and
	 * whose {@code data} falls within {@code [from, to]} (inclusive), computed entirely in the
	 * database ({@code BigDecimal} arithmetic, moved server-side because loading every transaction
	 * into memory to sum them, as {@code dcbo/src/pages/finances.js} does today, stops scaling once
	 * results are paginated).
	 *
	 * <p>Both bounds must be non-{@code null}: the caller substitutes safe,
	 * effectively-unbounded defaults (year 1 / year 9999, both squarely inside PostgreSQL's {@code
	 * DATE} range) rather than passing {@code null} through to a {@code (:param is null or ...)}
	 * guard — PostgreSQL cannot infer a parameter's type from an {@code IS NULL} check alone, and
	 * rejects the query at bind time ({@code could not determine data type of parameter}) when a
	 * parameter is only ever compared that way.
	 *
	 * @param tipos the {@link TransactionType} values to include (e.g. receita-like or
	 *     despesa-like), never empty
	 * @param from the inclusive lower bound of {@code data}
	 * @param to the inclusive upper bound of {@code data}
	 * @return the sum of matching {@code valor} values, or {@link BigDecimal#ZERO} if none match
	 */
	@Query(
			"select coalesce(sum(t.valor), 0) from Transaction t "
					+ "where t.tipo in :tipos and t.data between :from and :to")
	BigDecimal sumValorByTipoInAndDataBetween(
			@Param("tipos") Collection<TransactionType> tipos,
			@Param("from") LocalDate from,
			@Param("to") LocalDate to);
}
