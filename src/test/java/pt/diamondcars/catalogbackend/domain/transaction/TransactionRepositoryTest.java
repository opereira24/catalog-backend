package pt.diamondcars.catalogbackend.domain.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * {@code @DataJpaTest} for the {@link Transaction} aggregate: confirms it round-trips through the
 * real {@code transactions} table, that {@link TransactionType} survives
 * a save/reload cycle that actually hits the database via its converter, and exercises {@link
 * TransactionRepository#findByCarId(java.util.UUID)} (ported from {@code dcbo-backend}), plus the
 * two queries the TASK-005 transactions API consumes.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionRepositoryTest extends AbstractPostgresIntegrationTest {

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private CarRepository carRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private static Car aCar() {
		return Car.builder()
				.marca("Audi")
				.modelo("A4")
				.ano(2019)
				.preco(new BigDecimal("22000.00"))
				.km(80000)
				.cor("Branco")
				.combustivel("Gasolina")
				.transmissao("Manual")
				.origem("stand")
				.build();
	}

	/**
	 * Confirms a {@link Transaction} saved with a {@link TransactionType} enum value can be
	 * reloaded with that same enum constant intact.
	 *
	 * <p>Uses {@code saveAndFlush} and then clears the persistence context before reloading: a
	 * plain {@code save} followed by {@code findById} inside the same transaction would be served
	 * entirely by Hibernate's first-level cache — no {@code INSERT} or {@code SELECT} would ever
	 * reach the database, and the assertions below would pass regardless of whether the mapping or
	 * the enum converter are even correct.
	 *
	 * @throws AssertionError if the reloaded transaction does not match what was saved
	 */
	@Test
	void savesAndReloadsATransactionWithItsType() {
		Transaction saved = transactionRepository.saveAndFlush(Transaction.builder()
				.tipo(TransactionType.DESPESA)
				.valor(new BigDecimal("150.00"))
				.data(LocalDate.of(2026, 1, 15))
				.build());
		entityManager.clear();

		Transaction reloaded = transactionRepository.findById(saved.getId()).orElseThrow();

		assertThat(reloaded).isNotSameAs(saved);
		assertThat(reloaded.getTipo()).isEqualTo(TransactionType.DESPESA);
		assertThat(reloaded.getValor()).isEqualByComparingTo(new BigDecimal("150.00"));
	}

	/**
	 * Confirms {@link TransactionRepository#findByCarId(java.util.UUID)} returns only the
	 * transactions linked to the given car.
	 *
	 * @throws AssertionError if a transaction for a different car is included, or the linked one is
	 *         missing
	 */
	@Test
	void findsTransactionsLinkedToACar() {
		Car car = carRepository.saveAndFlush(aCar());
		Car otherCar = carRepository.saveAndFlush(aCar());

		Transaction linked = transactionRepository.save(Transaction.builder()
				.tipo(TransactionType.COMPRA)
				.valor(new BigDecimal("22000.00"))
				.data(LocalDate.of(2026, 1, 1))
				.car(car)
				.build());
		transactionRepository.save(Transaction.builder()
				.tipo(TransactionType.COMPRA)
				.valor(new BigDecimal("18000.00"))
				.data(LocalDate.of(2026, 1, 2))
				.car(otherCar)
				.build());

		assertThat(transactionRepository.findByCarId(car.getId()))
				.extracting(Transaction::getId)
				.containsExactly(linked.getId());
	}

	/**
	 * {@link TransactionRepository#findByCarIdAndSystemGeneratedTrue(java.util.UUID)} returns only the
	 * system-generated transactions of the given car (TASK-005 AC D.5): not that car's manual ones,
	 * not another car's system-generated ones.
	 */
	@Test
	void findsOnlyTheSystemGeneratedTransactionsOfACar() {
		Car car = carRepository.saveAndFlush(aCar());
		Car otherCar = carRepository.saveAndFlush(aCar());
		Transaction systemOfCar = transactionRepository.save(aVenda(car, true));
		transactionRepository.save(aVenda(car, false));
		transactionRepository.save(aVenda(otherCar, true));
		transactionRepository.flush();

		assertThat(transactionRepository.findByCarIdAndSystemGeneratedTrue(car.getId()))
				.extracting(Transaction::getId)
				.containsExactly(systemOfCar.getId());
	}

	/**
	 * {@link TransactionRepository#sumValorByTipoInAndDataBetween} counts both bounds, only the
	 * requested kinds, and is {@code 0} (by {@code compareTo}) when no row matches (TASK-005 AC D.5).
	 */
	@Test
	void sumsValorOfTheRequestedTiposWithInclusiveBoundsAndZeroWhenNothingMatches() {
		transactionRepository.save(withTipoValorData(TransactionType.RECEITA, "100.10", LocalDate.of(2026, 3, 1)));
		transactionRepository.save(withTipoValorData(TransactionType.VENDA, "200.20", LocalDate.of(2026, 3, 31)));
		transactionRepository.save(withTipoValorData(TransactionType.RECEITA, "1000.00", LocalDate.of(2026, 2, 28)));
		transactionRepository.save(withTipoValorData(TransactionType.RECEITA, "1000.00", LocalDate.of(2026, 4, 1)));
		transactionRepository.save(withTipoValorData(TransactionType.DESPESA, "50.00", LocalDate.of(2026, 3, 15)));
		transactionRepository.flush();
		LocalDate from = LocalDate.of(2026, 3, 1);
		LocalDate to = LocalDate.of(2026, 3, 31);

		assertThat(transactionRepository.sumValorByTipoInAndDataBetween(
				Set.of(TransactionType.RECEITA, TransactionType.VENDA), from, to))
				.isEqualByComparingTo("300.30");
		assertThat(transactionRepository.sumValorByTipoInAndDataBetween(Set.of(TransactionType.DESPESA), from, to))
				.isEqualByComparingTo("50.00");
		assertThat(transactionRepository.sumValorByTipoInAndDataBetween(Set.of(TransactionType.COMPRA), from, to))
				.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(transactionRepository.sumValorByTipoInAndDataBetween(
				Set.of(TransactionType.RECEITA), LocalDate.of(2030, 1, 1), LocalDate.of(2030, 12, 31)))
				.isEqualByComparingTo(BigDecimal.ZERO);
	}

	private static Transaction aVenda(Car car, boolean systemGenerated) {
		return Transaction.builder()
				.tipo(TransactionType.VENDA)
				.valor(new BigDecimal("21000.00"))
				.data(LocalDate.of(2026, 1, 1))
				.car(car)
				.systemGenerated(systemGenerated)
				.build();
	}

	private static Transaction withTipoValorData(TransactionType tipo, String valor, LocalDate data) {
		return Transaction.builder().tipo(tipo).valor(new BigDecimal(valor)).data(data).build();
	}
}
