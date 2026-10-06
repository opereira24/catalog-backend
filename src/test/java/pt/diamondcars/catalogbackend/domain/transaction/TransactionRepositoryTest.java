package pt.diamondcars.catalogbackend.domain.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * TransactionRepository#findByCarId(java.util.UUID)} (ported from {@code dcbo-backend}).
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
}
