package pt.diamondcars.catalogbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.client.Client;
import pt.diamondcars.catalogbackend.domain.client.ClientRepository;
import pt.diamondcars.catalogbackend.domain.partner.Partner;
import pt.diamondcars.catalogbackend.domain.partner.PartnerRepository;
import pt.diamondcars.catalogbackend.domain.transaction.Transaction;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionRepository;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionType;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;
import pt.diamondcars.catalogbackend.web.dto.FinanceSummaryResponse;
import pt.diamondcars.catalogbackend.web.dto.TransactionRequest;

/**
 * Integration tests of the three {@link TransactionService} hooks the car sale of TASK-010 calls ({@link
 * TransactionService#createSaleTransaction}, {@link TransactionService#deleteSaleTransactions},
 * {@link TransactionService#deleteAllForCar}) and of the date clamp of {@link
 * TransactionService#summary}, calling the service directly against the shared PostgreSQL container
 * (TASK-005, AC D.3). Same context configuration as {@code ClientControllerTest} (the {@code
 * AutoConfigureMockMvc} is there only so the cached context is the same one), no mocks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class TransactionServiceTest extends AbstractPostgresIntegrationTest {

	@Autowired private TransactionService transactionService;
	@Autowired private TransactionRepository transactionRepository;
	@Autowired private CarRepository carRepository;
	@Autowired private ClientRepository clientRepository;
	@Autowired private PartnerRepository partnerRepository;
	@Autowired private PlatformTransactionManager transactionManager;

	/** Shared container, no per-test rollback: transactions first, they reference the rest. */
	@BeforeEach
	void cleanDatabase() {
		transactionRepository.deleteAllInBatch();
		carRepository.deleteAllInBatch();
		clientRepository.deleteAllInBatch();
		partnerRepository.deleteAllInBatch();
	}

	private Car aCar(Client client, Partner partner) {
		return carRepository.saveAndFlush(Car.builder()
				.marca("Audi")
				.modelo("A4")
				.ano(2019)
				.preco(new BigDecimal("22000.00"))
				.km(80000)
				.cor("Branco")
				.combustivel("Gasolina")
				.transmissao("Manual")
				.origem("stand")
				.client(client)
				.partner(partner)
				.build());
	}

	private Transaction aTransaction(Car car, TransactionType tipo, boolean systemGenerated) {
		return transactionRepository.saveAndFlush(Transaction.builder()
				.tipo(tipo)
				.valor(new BigDecimal("100.00"))
				.data(LocalDate.of(2026, 1, 1))
				.car(car)
				.systemGenerated(systemGenerated)
				.build());
	}

	/**
	 * A sale of a car with a buyer and a consignment partner records one system-generated {@code
	 * venda} with the given amount, texts, the car, the car's client and the partner, dated today in
	 * Lisbon.
	 */
	@Test
	void createSaleTransactionRecordsASystemGeneratedVendaForTheCarItsBuyerAndPartner() {
		Client buyer = clientRepository.saveAndFlush(Client.builder().name("Ana").phone("912345678").build());
		Partner partner = partnerRepository.saveAndFlush(Partner.builder().name("Auto Silva").build());
		Car car = aCar(buyer, partner);

		LocalDate before = LocalDate.now(TransactionRequest.TRANSACTION_ZONE);
		transactionService.createSaleTransaction(
				car, new BigDecimal("1000.00"), "Comissao venda Audi A4", "comissao_venda", partner);
		LocalDate after = LocalDate.now(TransactionRequest.TRANSACTION_ZONE);

		List<Transaction> stored = transactionRepository.findByCarId(car.getId());
		assertThat(stored).hasSize(1);
		Transaction sale = stored.getFirst();
		assertThat(sale.getTipo()).isEqualTo(TransactionType.VENDA);
		assertThat(sale.getValor()).isEqualByComparingTo("1000.00");
		assertThat(sale.getDescricao()).isEqualTo("Comissao venda Audi A4");
		assertThat(sale.getCategoria()).isEqualTo("comissao_venda");
		assertThat(sale.isSystemGenerated()).isTrue();
		assertThat(sale.getCar().getId()).isEqualTo(car.getId());
		assertThat(sale.getClient().getId()).isEqualTo(buyer.getId());
		assertThat(sale.getPartner().getId()).isEqualTo(partner.getId());
		assertThat(sale.getData()).isIn(before, after);
	}

	/** Without a partner (not a consignment sale) the transaction has no partner. */
	@Test
	void createSaleTransactionWithoutAPartnerLeavesThePartnerEmpty() {
		Car car = aCar(null, null);

		transactionService.createSaleTransaction(car, new BigDecimal("21000.00"), "Venda Audi A4", "venda_veiculo", null);

		Transaction sale = transactionRepository.findByCarId(car.getId()).getFirst();
		assertThat(sale.getPartner()).isNull();
		assertThat(sale.getClient()).isNull();
		assertThat(sale.getValor()).isEqualByComparingTo("21000.00");
	}

	/**
	 * Reverting the sale of car A removes only A's system-generated transactions: A's manual {@code
	 * venda} and B's system-generated one stay.
	 */
	@Test
	void deleteSaleTransactionsRemovesOnlyTheSystemGeneratedOnesOfThatCar() {
		Car carA = aCar(null, null);
		Car carB = aCar(null, null);
		aTransaction(carA, TransactionType.VENDA, true);
		Transaction manualOfA = aTransaction(carA, TransactionType.VENDA, false);
		Transaction systemOfB = aTransaction(carB, TransactionType.VENDA, true);

		transactionService.deleteSaleTransactions(carA.getId());

		assertThat(transactionRepository.findAll())
				.extracting(Transaction::getId)
				.containsExactlyInAnyOrder(manualOfA.getId(), systemOfB.getId());
	}

	/** Deleting car A removes all of A's transactions, manual included; B's stays. */
	@Test
	void deleteAllForCarRemovesEveryTransactionOfThatCarOnly() {
		Car carA = aCar(null, null);
		Car carB = aCar(null, null);
		aTransaction(carA, TransactionType.VENDA, true);
		aTransaction(carA, TransactionType.DESPESA, false);
		Transaction manualOfB = aTransaction(carB, TransactionType.COMPRA, false);

		transactionService.deleteAllForCar(carA.getId());

		assertThat(transactionRepository.findAll())
				.extracting(Transaction::getId)
				.containsExactly(manualOfB.getId());
	}

	/**
	 * The sale hook joins the caller's transaction ({@code REQUIRED}): when the caller fails after
	 * it, the financial movement is rolled back with the rest. With {@code REQUIRES_NEW} it would be
	 * committed on its own and stay.
	 */
	@Test
	void createSaleTransactionRollsBackWithTheCallersTransaction() {
		Car car = aCar(null, null);
		TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);

		assertThatThrownBy(() -> callerTransaction.executeWithoutResult(status -> {
			transactionService.createSaleTransaction(car, new BigDecimal("21000.00"), "Venda", "venda_veiculo", null);
			throw new IllegalStateException("sale failed after recording the transaction");
		}))
				.isInstanceOf(IllegalStateException.class);

		assertThat(transactionRepository.count()).isZero();
	}

	/**
	 * A {@code from} far before year 1 (outside PostgreSQL's {@code DATE}, which would fail the
	 * query) is clamped to {@link TransactionRequest#MIN_DATA}: same result as no bound at all.
	 */
	@Test
	void summaryClampsAFromBeforeYearOneToTheWholeHistory() {
		transactionRepository.saveAndFlush(Transaction.builder()
				.tipo(TransactionType.RECEITA)
				.valor(new BigDecimal("42.00"))
				.data(LocalDate.of(2026, 1, 1))
				.build());

		FinanceSummaryResponse clamped = transactionService.summary(LocalDate.of(-5000, 1, 1), null);

		assertThat(clamped).isEqualTo(transactionService.summary(null, null));
		assertThat(clamped.totalReceitas()).isEqualByComparingTo("42.00");
	}
}
