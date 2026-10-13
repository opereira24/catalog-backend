package pt.diamondcars.catalogbackend.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.partner.Partner;
import pt.diamondcars.catalogbackend.domain.transaction.Transaction;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionRepository;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionType;
import pt.diamondcars.catalogbackend.web.dto.FinanceSummaryResponse;
import pt.diamondcars.catalogbackend.web.dto.TransactionRequest;
import pt.diamondcars.catalogbackend.web.dto.TransactionResponse;
import pt.diamondcars.catalogbackend.web.exception.InvalidReferenceException;
import pt.diamondcars.catalogbackend.web.exception.ResourceNotFoundException;
import pt.diamondcars.catalogbackend.web.exception.UnknownSortPropertyException;

/**
 * Business logic of {@code /api/backoffice/transactions} and {@code /api/backoffice/finances}
 * (TASK-005, AC C.2), ported from {@code dcbo-backend}'s {@code TransactionService}, plus the three
 * hooks {@code BackOfficeCarService}'s sale, sale reversal and car deletion call (TASK-010): {@link
 * #createSaleTransaction}, {@link #deleteSaleTransactions} and {@link #deleteAllForCar}. {@code
 * TransactionServiceTest} and {@code TransactionServiceSaleDateTest} prove them in isolation; the
 * car tests prove them through the car endpoints.
 *
 * <p>Every public method is {@code @Transactional} and maps to a DTO before returning: with {@code
 * spring.jpa.open-in-view: false}, mapping must happen while the persistence context is open. The
 * three hooks use the default propagation ({@code REQUIRED}), never {@code REQUIRES_NEW}: they run
 * inside the caller's transaction, so a failure after them rolls the financial movement back with
 * the rest of the sale.
 */
@Service
public class TransactionService {

	/**
	 * Properties a listing may sort by: only the scalar columns of {@link Transaction}. {@code
	 * Transaction} has three {@code LAZY} associations ({@code car}, {@code client}, {@code
	 * partner}); without this list Spring Data accepts {@code sort=car.marca} or {@code
	 * sort=partner.name} as a join (TASK-005 AC A.2, {@code web/CLAUDE.md}). {@code tipo} sorts by
	 * the stored text ({@code compra} < {@code despesa} < {@code receita} < {@code venda}).
	 */
	static final Set<String> SORTABLE_PROPERTIES = Set.of(
			"id", "createdAt", "updatedAt", "tipo", "valor", "descricao", "categoria", "data", "systemGenerated");

	/** Kinds the summary counts as money in: the {@code vendas + receitas} of {@code dcbo/src/App.js:197,200}. */
	static final Set<TransactionType> RECEITA_LIKE_TYPES = Set.of(TransactionType.RECEITA, TransactionType.VENDA);

	/** Kinds the summary counts as money out: the {@code compras + despesas} of {@code dcbo/src/App.js:198-199}. */
	static final Set<TransactionType> DESPESA_LIKE_TYPES = Set.of(TransactionType.DESPESA, TransactionType.COMPRA);

	private final TransactionRepository transactionRepository;
	private final CarRepository carRepository;
	private final Clock clock;

	/**
	 * Creates the service used by the application, with the system clock in UTC (only {@link
	 * #createSaleTransaction} reads it, and always converts the instant to {@link
	 * TransactionRequest#TRANSACTION_ZONE}).
	 *
	 * @param transactionRepository persistence for {@link Transaction}
	 * @param carRepository persistence for {@link Car}, to resolve the {@code carroId} of a manual
	 *     transaction
	 */
	@Autowired
	public TransactionService(TransactionRepository transactionRepository, CarRepository carRepository) {
		this(transactionRepository, carRepository, Clock.systemUTC());
	}

	/**
	 * Creates the service with a given clock. Package-private and only for {@code
	 * TransactionServiceSaleDateTest}, which fixes the instant of a sale to prove its date in
	 * Lisbon. Not a {@code Clock} bean on purpose: a bean would invite other code to depend on it,
	 * and replacing it in a test would cost a new Spring context (10 connections, {@code
	 * web/CLAUDE.md}).
	 *
	 * @param transactionRepository persistence for {@link Transaction}
	 * @param carRepository persistence for {@link Car}
	 * @param clock source of the current instant for {@link #createSaleTransaction}
	 */
	TransactionService(TransactionRepository transactionRepository, CarRepository carRepository, Clock clock) {
		this.transactionRepository = transactionRepository;
		this.carRepository = carRepository;
		this.clock = clock;
	}

	/**
	 * Lists transactions, optionally filtered by {@code tipo}, {@code carroId} and an inclusive date
	 * range.
	 *
	 * @param tipo required {@code tipo}, or {@code null} to not filter by it
	 * @param carroId required car, or {@code null} to not filter by it
	 * @param from inclusive lower bound of {@code data}, or {@code null}
	 * @param to inclusive upper bound of {@code data}, or {@code null}
	 * @param pageable pagination and sorting (the controller defaults to {@code data, createdAt, id}
	 *     descending, 50 per page); {@code id} DESC is appended when the sort lacks it ({@link
	 *     StableSort#withIdTiebreak}), after the allow-list check
	 * @return the requested page
	 * @throws UnknownSortPropertyException if a sort property is outside {@link
	 *     #SORTABLE_PROPERTIES} (mapped to 400), checked before any query
	 */
	@Transactional(readOnly = true)
	public Page<TransactionResponse> list(
			TransactionType tipo, UUID carroId, LocalDate from, LocalDate to, Pageable pageable) {
		requireSortable(pageable.getSort());
		Pageable stablePageable = StableSort.withIdTiebreak(pageable);
		LocalDate effectiveFrom = from != null ? lowerBound(from) : null;
		LocalDate effectiveTo = to != null ? upperBound(to) : null;
		return transactionRepository
				.findAll(TransactionSpecifications.matching(tipo, carroId, effectiveFrom, effectiveTo), stablePageable)
				.map(TransactionResponse::from);
	}

	/**
	 * Fetches a single transaction.
	 *
	 * @param id the transaction's id
	 * @return the matching transaction
	 * @throws ResourceNotFoundException if no transaction has this id (mapped to 404)
	 */
	@Transactional(readOnly = true)
	public TransactionResponse get(UUID id) {
		return TransactionResponse.from(findOrThrow(id));
	}

	/**
	 * Creates a manual transaction ({@code addTransaction} of {@code
	 * dcbo/src/services/firebaseService.js:380}): never system-generated, never with a client or a
	 * partner, whatever the body carries.
	 *
	 * @param request the validated payload
	 * @return the created transaction
	 * @throws InvalidReferenceException if {@code carroId} is given and no car has it (mapped to 400)
	 */
	@Transactional
	public TransactionResponse create(TransactionRequest request) {
		Transaction transaction = new Transaction();
		applyBusinessFields(transaction, request);
		transaction.setCar(request.carroId() != null ? requireCar(request.carroId()) : null);
		transaction.setSystemGenerated(false);
		return TransactionResponse.from(transactionRepository.save(transaction));
	}

	/**
	 * Updates the 5 business fields of a transaction ({@code tipo}, {@code valor}, {@code
	 * descricao}, {@code categoria}, {@code data}). Never touches the car, the client, the partner or
	 * {@code systemGenerated}, also for system-generated transactions (the "Editar" of {@code dcbo}
	 * opens every one of them; see {@link TransactionRequest} for why {@code carroId} is ignored).
	 *
	 * @param id the transaction's id
	 * @param request the validated payload
	 * @return the updated transaction
	 * @throws ResourceNotFoundException if no transaction has this id (mapped to 404)
	 */
	@Transactional
	public TransactionResponse update(UUID id, TransactionRequest request) {
		Transaction transaction = findOrThrow(id);
		applyBusinessFields(transaction, request);
		return TransactionResponse.from(transaction);
	}

	/**
	 * Deletes a transaction, system-generated ones included (parity with {@code dcbo}); reverting
	 * that sale later then finds nothing to delete.
	 *
	 * @param id the transaction's id
	 * @throws ResourceNotFoundException if no transaction has this id (mapped to 404)
	 */
	@Transactional
	public void delete(UUID id) {
		transactionRepository.delete(findOrThrow(id));
	}

	/**
	 * Computes the finances summary over an inclusive date range, manual and system-generated
	 * transactions alike.
	 *
	 * <p>Two sums, two statements, in {@code READ COMMITTED}: a transaction committed between them
	 * may count in one total and not the other. {@code saldo} is always the difference of the two
	 * numbers returned. Accepted for a summary screen; not worth {@code REPEATABLE READ}.
	 *
	 * @param from inclusive lower bound of {@code data}, or {@code null} for no lower bound
	 * @param to inclusive upper bound of {@code data}, or {@code null} for no upper bound
	 * @return the summary, every amount with 2 decimal places
	 */
	@Transactional(readOnly = true)
	public FinanceSummaryResponse summary(LocalDate from, LocalDate to) {
		LocalDate effectiveFrom = from != null ? lowerBound(from) : TransactionRequest.MIN_DATA;
		LocalDate effectiveTo = to != null ? upperBound(to) : TransactionRequest.MAX_DATA;
		BigDecimal totalReceitas =
				transactionRepository.sumValorByTipoInAndDataBetween(RECEITA_LIKE_TYPES, effectiveFrom, effectiveTo);
		BigDecimal totalDespesas =
				transactionRepository.sumValorByTipoInAndDataBetween(DESPESA_LIKE_TYPES, effectiveFrom, effectiveTo);
		return FinanceSummaryResponse.of(totalReceitas, totalDespesas);
	}

	/**
	 * Records the system-generated {@code venda} of a car sale (TASK-010's {@code sell}), dated today
	 * in {@code Europe/Lisbon}: the commission for a consignment sale ({@code partner} given), the
	 * sale price otherwise. Runs in the caller's transaction.
	 *
	 * @param car the car just sold, with {@link Car#getClient()} already the buyer (or {@code null})
	 * @param valor the amount; validated by the caller, not here (a commission of 0 is stored as
	 *     {@code 0.00}, as {@code dcbo} does today)
	 * @param descricao human-readable description
	 * @param categoria {@code comissao_venda} for a consignment sale, {@code venda_veiculo} otherwise
	 * @param partner the consignment partner, or {@code null}
	 * @throws NullPointerException if {@code car} or {@code valor} is {@code null}
	 */
	@Transactional
	public void createSaleTransaction(Car car, BigDecimal valor, String descricao, String categoria, Partner partner) {
		Objects.requireNonNull(car, "car");
		Objects.requireNonNull(valor, "valor");
		Transaction transaction = new Transaction();
		transaction.setTipo(TransactionType.VENDA);
		transaction.setValor(valor);
		transaction.setDescricao(descricao);
		transaction.setCategoria(categoria);
		transaction.setData(LocalDate.ofInstant(clock.instant(), TransactionRequest.TRANSACTION_ZONE));
		transaction.setCar(car);
		transaction.setClient(car.getClient());
		transaction.setPartner(partner);
		transaction.setSystemGenerated(true);
		transactionRepository.save(transaction);
	}

	/**
	 * Deletes only the system-generated transactions of a car (TASK-010's sale reversal), never a
	 * manual one that shares the car or the {@code tipo}. Runs in the caller's transaction.
	 *
	 * @param carId the car whose sale is being reverted
	 */
	@Transactional
	public void deleteSaleTransactions(UUID carId) {
		transactionRepository.deleteAll(transactionRepository.findByCarIdAndSystemGeneratedTrue(carId));
	}

	/**
	 * Deletes every transaction of a car, manual ones included (TASK-010's car deletion, {@code
	 * deleteCarTransactions} of {@code dcbo/src/services/firebaseService.js:415}). Runs in the
	 * caller's transaction.
	 *
	 * @param carId the car being deleted
	 */
	@Transactional
	public void deleteAllForCar(UUID carId) {
		transactionRepository.deleteAll(transactionRepository.findByCarId(carId));
	}

	/**
	 * Lower bound actually sent to PostgreSQL: before {@link TransactionRequest#MIN_DATA} becomes
	 * {@code MIN_DATA}; after {@link TransactionRequest#MAX_DATA} becomes the day after it. Neither
	 * changes which rows match, because no stored {@code data} is outside {@code [MIN_DATA,
	 * MAX_DATA]} ({@link TransactionRequest#resolveData()}); both keep a year like {@code -5000} or
	 * {@code +999999999}, which {@code ?from=} parses, out of PostgreSQL ({@code DATE} goes from 4713
	 * BC to 5874897 AD; outside it the query fails with {@code date out of range}, which reaches
	 * the caller as a 409 through the {@code DataIntegrityViolationException} handler, measured).
	 */
	private static LocalDate lowerBound(LocalDate from) {
		if (from.isBefore(TransactionRequest.MIN_DATA)) {
			return TransactionRequest.MIN_DATA;
		}
		return from.isAfter(TransactionRequest.MAX_DATA) ? TransactionRequest.MAX_DATA.plusDays(1) : from;
	}

	/** Upper bound actually sent to PostgreSQL; mirror image of {@link #lowerBound(LocalDate)}. */
	private static LocalDate upperBound(LocalDate to) {
		if (to.isAfter(TransactionRequest.MAX_DATA)) {
			return TransactionRequest.MAX_DATA;
		}
		return to.isBefore(TransactionRequest.MIN_DATA) ? TransactionRequest.MIN_DATA.minusDays(1) : to;
	}

	private static void applyBusinessFields(Transaction transaction, TransactionRequest request) {
		transaction.setTipo(TransactionType.fromValue(request.tipo()));
		transaction.setValor(request.valor());
		transaction.setDescricao(request.descricao());
		transaction.setCategoria(request.categoria());
		transaction.setData(request.resolveData());
	}

	private static void requireSortable(Sort sort) {
		for (Sort.Order order : sort) {
			if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
				throw new UnknownSortPropertyException(order.getProperty());
			}
		}
	}

	private Transaction findOrThrow(UUID id) {
		return transactionRepository
				.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Transacao nao encontrada: " + id));
	}

	private Car requireCar(UUID carId) {
		return carRepository
				.findById(carId)
				.orElseThrow(() -> new InvalidReferenceException("carroId: carro nao encontrado: " + carId));
	}
}
