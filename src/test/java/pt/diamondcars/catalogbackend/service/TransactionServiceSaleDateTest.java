package pt.diamondcars.catalogbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.transaction.Transaction;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionRepository;

/**
 * The date of a system-generated sale is the calendar day in Lisbon of the instant of the sale,
 * never the UTC day (TASK-005, AC D.4). Plain JUnit and Mockito, no Spring and no database: the
 * package-private constructor of {@link TransactionService} takes a fixed {@link Clock} in UTC, so
 * an implementation that used the clock's own zone gets the UTC day and fails.
 */
class TransactionServiceSaleDateTest {

	/**
	 * {@code 23:30Z} on 31 March 2026 is already 1 April in Lisbon (summer time, UTC+1); {@code
	 * 22:30Z} is still 31 March. Together they rule out "the UTC day" and "always the next day".
	 *
	 * @param instant the instant of the sale
	 * @param expectedData the date the sale transaction must carry
	 */
	@ParameterizedTest(name = "{0} -> {1}")
	@CsvSource({"2026-03-31T23:30:00Z, 2026-04-01", "2026-03-31T22:30:00Z, 2026-03-31"})
	void theSaleIsDatedOnItsLisbonCalendarDay(String instant, String expectedData) {
		TransactionRepository transactionRepository = mock(TransactionRepository.class);
		TransactionService service = new TransactionService(
				transactionRepository, mock(CarRepository.class), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));

		service.createSaleTransaction(new Car(), new BigDecimal("21000.00"), "Venda", "venda_veiculo", null);

		ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
		verify(transactionRepository).save(saved.capture());
		assertThat(saved.getValue().getData()).isEqualTo(LocalDate.parse(expectedData));
	}
}
