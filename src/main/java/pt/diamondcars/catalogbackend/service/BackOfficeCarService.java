package pt.diamondcars.catalogbackend.service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarImage;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.client.Client;
import pt.diamondcars.catalogbackend.domain.client.ClientRepository;
import pt.diamondcars.catalogbackend.domain.partner.Partner;
import pt.diamondcars.catalogbackend.domain.partner.PartnerRepository;
import pt.diamondcars.catalogbackend.web.dto.BackOfficeCarResponse;
import pt.diamondcars.catalogbackend.web.dto.CarRequest;
import pt.diamondcars.catalogbackend.web.dto.HighlightRequest;
import pt.diamondcars.catalogbackend.web.dto.SellCarRequest;
import pt.diamondcars.catalogbackend.web.exception.CarAlreadySoldException;
import pt.diamondcars.catalogbackend.web.exception.HighlightLimitExceededException;
import pt.diamondcars.catalogbackend.web.exception.ResourceNotFoundException;
import pt.diamondcars.catalogbackend.web.exception.UnknownSortPropertyException;

/**
 * Business logic of the back-office car API ({@code /api/backoffice/cars}): list, get, create,
 * reserve, release a reservation and feature (TASK-003); update, delete, sell and revert a sale
 * (TASK-010). Ported from {@code dcbo-backend}'s {@code CarService}.
 *
 * <p>Every method maps to {@link BackOfficeCarResponse} inside its own transaction and never
 * returns the entity: with {@code open-in-view: false}, the lazy photos would fail after it.
 *
 * <p>Side effects on money, as in {@code dcbo-backend}: a partner's {@code cars_count}/{@code
 * total_commission}, a client's {@code purchases_count} and the system-generated sale transaction
 * only change on {@link #sell}, {@link #revertSale}, the {@link #update} of a sold car (partner
 * counters only) and the {@link #delete} of a sold car with its transactions (client counter only).
 * Create, reserve, release and highlight never touch them.
 *
 * <p><b>Concurrency (TASK-010, AC C.1).</b> Every write to an existing car reads it first with its
 * row locked ({@link #lockCar}); partners and clients whose counters change are read with their row
 * locked too. Hibernate writes whole rows with the values it read, so without these locks two
 * concurrent operations on the same car, partner or client lose one of the writes (a reservation
 * erasing a sale, two sales to the same buyer counting one). Lock order, always the same so two
 * transactions never wait on each other in a cycle: the car, then the highlight advisory lock (only
 * when turning a highlight on), then partners by ascending id, then the client; all before the
 * first write. Each lock must also be the first load of that entity in the transaction (only the id
 * of a {@code LAZY} proxy may be read before), or Hibernate keeps the stale state it had in memory.
 */
@Service
public class BackOfficeCarService {

	/**
	 * Maximum number of featured cars ({@code destaque = true}) at the same time, the limit {@code
	 * dcbo/src/pages/highlights.js} enforces in the browser today. Counts every featured car, sold
	 * ones included, like {@code dcbo-backend} and {@code dcbo}.
	 */
	static final int HIGHLIGHT_LIMIT = 8;

	/**
	 * Properties the listing accepts in {@code sort} (TASK-003, AC D.3): only scalar columns of
	 * {@link Car}. Anything else is a 400 before the query runs; in particular a path through an
	 * association ({@code images.url}, {@code partner.name}, {@code client.name}) would be accepted
	 * by Spring Data as a join, and a join to the photos repeats the same car across pages.
	 */
	static final Set<String> SORTABLE_PROPERTIES = Set.of(
			"id", "createdAt", "updatedAt", "marca", "modelo", "ano", "preco", "km", "cor", "combustivel",
			"transmissao", "origem", "garantiaMeses", "vendido", "reservado", "destaque", "dataCompra",
			"dataVenda", "precoCompra", "precoVenda", "commissionValue");

	/**
	 * Order in which two partners are locked: the textual form of a UUID sorts like PostgreSQL's
	 * {@code ORDER BY id} (byte order). Any fixed order rules out a deadlock; this one is the one a
	 * reader of the database expects.
	 */
	private static final Comparator<UUID> LOCK_ORDER = Comparator.comparing(UUID::toString);

	private final CarRepository carRepository;
	private final PartnerRepository partnerRepository;
	private final ClientRepository clientRepository;
	private final PartnerService partnerService;
	private final TransactionService transactionService;

	/**
	 * Creates the service with its collaborators.
	 *
	 * @param carRepository persistence of {@link Car}, its row lock and the highlight advisory lock
	 * @param partnerRepository resolves {@link CarRequest#partnerId()} and locks partners whose
	 *     counters change
	 * @param clientRepository locks the buying client whose {@code purchases_count} changes
	 * @param partnerService the partner counter hooks (TASK-004)
	 * @param transactionService the sale transaction hooks (TASK-005)
	 */
	public BackOfficeCarService(
			CarRepository carRepository,
			PartnerRepository partnerRepository,
			ClientRepository clientRepository,
			PartnerService partnerService,
			TransactionService transactionService) {
		this.carRepository = carRepository;
		this.partnerRepository = partnerRepository;
		this.clientRepository = clientRepository;
		this.partnerService = partnerService;
		this.transactionService = transactionService;
	}

	/**
	 * Lists cars, optionally filtered by {@code vendido}/{@code reservado}/{@code destaque} (combined
	 * with AND).
	 *
	 * @param vendido required value of {@code vendido}, or {@code null} to not filter by it
	 * @param reservado required value of {@code reservado}, or {@code null} to not filter by it
	 * @param destaque required value of {@code destaque}, or {@code null} to not filter by it
	 * @param pageable page, size (capped globally by {@code spring.data.web.pageable.max-page-size})
	 *     and sort, defaulted by the controller
	 * @return the requested page
	 * @throws UnknownSortPropertyException if a sort property is not in {@link #SORTABLE_PROPERTIES}
	 *     (mapped to 400), checked before any query
	 */
	@Transactional(readOnly = true)
	public Page<BackOfficeCarResponse> list(
			Boolean vendido, Boolean reservado, Boolean destaque, Pageable pageable) {
		requireSortable(pageable.getSort());
		return carRepository
				.findAll(BackOfficeCarSpecifications.matching(vendido, reservado, destaque), pageable)
				.map(BackOfficeCarResponse::from);
	}

	/**
	 * Fetches a single car. The only read without a row lock: it writes nothing.
	 *
	 * @param id the car's id
	 * @return the car
	 * @throws ResourceNotFoundException if no car has this id (mapped to 404)
	 */
	@Transactional(readOnly = true)
	public BackOfficeCarResponse get(UUID id) {
		return BackOfficeCarResponse.from(carRepository.findById(id).orElseThrow(() -> carNotFound(id)));
	}

	/**
	 * Creates a car from the request, with the null semantics of {@link CarRequest} (AC C.3). A new
	 * car is never sold nor reserved and has no client: those keys are not part of the request. Only
	 * the highlight advisory lock: the new row is visible to no one else until the commit.
	 *
	 * @param request the validated request
	 * @return the created car
	 * @throws ResourceNotFoundException if {@link CarRequest#partnerId()} does not exist (mapped to
	 *     404)
	 * @throws HighlightLimitExceededException if the car is created featured and 8 cars already are
	 *     (mapped to 409)
	 */
	@Transactional
	public BackOfficeCarResponse create(CarRequest request) {
		Partner partner = request.partnerId() != null ? requirePartner(request.partnerId()) : null;
		boolean destaque = Boolean.TRUE.equals(request.destaque());
		assertHighlightLimitRespected(false, destaque);

		Car car = Car.builder()
				.marca(request.marca())
				.modelo(request.modelo())
				.ano(request.ano())
				.preco(request.preco())
				.km(request.km())
				.cor(request.cor())
				.combustivel(request.combustivel())
				.transmissao(request.transmissao())
				.origem(request.origem())
				.descricao(request.descricao())
				.observacoes(blankToNull(request.observacoes()))
				.precoCompra(request.precoCompra())
				.dataCompra(request.dataCompra())
				.consignacao(Boolean.TRUE.equals(request.isConsignacao()))
				.partner(partner)
				.commissionValue(request.commissionValue())
				.garantiaMeses(request.garantiaMeses() != null ? request.garantiaMeses() : 0)
				.destaque(destaque)
				.build();
		addImages(car, request.images(), request.imageThumbnails());
		return BackOfficeCarResponse.from(carRepository.saveAndFlush(car));
	}

	/**
	 * Replaces a car's data with the request, with the {@code PUT} null semantics of {@link
	 * CarRequest} (TASK-003 AC C.3): {@code observacoes} absent keeps, blank clears, text replaces;
	 * {@code destaque} absent keeps, {@code false} removes, {@code true} is subject to the 8-car
	 * limit; {@code images} absent keeps the photos, {@code []} removes them all; {@code
	 * isConsignacao}/{@code garantiaMeses} null are {@code false}/0; every other optional field is
	 * replaced, null included. Sale state ({@code vendido}, {@code precoVenda}, {@code dataVenda},
	 * the client) is not part of the request and never changes here.
	 *
	 * <p>For an unsold car no counter or transaction changes. For a sold car the commission the sale
	 * registered moves from the partner/value it had to the partner/value it has now ({@link
	 * #reconcileConsignmentSaleAfterEdit}), so a later {@link #revertSale}, which reverses with the
	 * car's current values, undoes exactly what was registered. The sale transaction keeps its
	 * original partner and value (parity with {@code dcbo-backend}, ASSUNÇÃO of AC C.4).
	 *
	 * <p>Locks: the car, then the highlight slot (only when turning the highlight on), then, for a
	 * sold car, the partner the sale registered on and the requested partner, by ascending id; the
	 * requested partner is loaded by that lock directly, never first by a plain lookup. A 404 or 409
	 * leaves everything untouched, reconciliation included.
	 *
	 * @param id the car's id
	 * @param request the validated request
	 * @return the updated car
	 * @throws ResourceNotFoundException if the car or {@link CarRequest#partnerId()} does not exist
	 *     (mapped to 404)
	 * @throws HighlightLimitExceededException if the request features the car and 8 cars already are
	 *     (mapped to 409)
	 */
	@Transactional
	public BackOfficeCarResponse update(UUID id, CarRequest request) {
		Car car = lockCar(id);
		boolean destaque = request.destaque() != null ? request.destaque() : car.isDestaque();
		assertHighlightLimitRespected(car.isDestaque(), destaque);

		if (!car.isVendido()) {
			Partner partner = request.partnerId() != null ? requirePartner(request.partnerId()) : null;
			applyUpdate(car, request, destaque, partner);
			return BackOfficeCarResponse.from(carRepository.saveAndFlush(car));
		}

		UUID registeredPartnerId = consignmentPartnerId(car);
		BigDecimal registeredCommission = car.getCommissionValue();
		Map<UUID, Partner> partners = lockPartners(registeredPartnerId, request.partnerId());
		Partner registeredPartner = registeredPartnerId != null ? partners.get(registeredPartnerId) : null;
		Partner requestedPartner = request.partnerId() != null ? partners.get(request.partnerId()) : null;

		applyUpdate(car, request, destaque, requestedPartner);
		reconcileConsignmentSaleAfterEdit(registeredPartner, registeredCommission, car);
		return BackOfficeCarResponse.from(carRepository.saveAndFlush(car));
	}

	/**
	 * Deletes a car and its photos ({@code orphanRemoval} on {@link Car#getImages()} and {@code ON
	 * DELETE CASCADE} on {@code car_images}). Its leads stay, without car ({@code ON DELETE SET NULL}).
	 *
	 * <p>With {@code deleteTransactions} (the default, {@code dcbo}'s checkbox checked) every
	 * transaction of the car is deleted, manual ones included, and, if the car was sold to a client,
	 * that client's {@code purchases_count} goes down by one (never below 0), as {@code dcbo}'s {@code
	 * deleteCar} does. Without it the transactions stay, detached from the car by {@code
	 * transactions.car_id ON DELETE SET NULL}, and no counter changes. A consignment partner's
	 * counters never change in either case (parity with {@code dcbo-backend} and {@code dcbo}).
	 *
	 * @param id the car's id
	 * @param deleteTransactions whether to delete the car's transactions and decrement its buyer
	 * @throws ResourceNotFoundException if no car has this id (mapped to 404)
	 */
	@Transactional
	public void delete(UUID id, boolean deleteTransactions) {
		Car car = lockCar(id);
		if (deleteTransactions) {
			Client buyer = car.isVendido() && car.getClient() != null ? lockClient(car.getClient().getId()) : null;
			transactionService.deleteAllForCar(id);
			if (buyer != null) {
				decrementPurchases(buyer);
			}
		}
		carRepository.delete(car);
	}

	/**
	 * Sells a car: {@code vendido}, {@code precoVenda}, {@code dataVenda} (now) and the buyer; the
	 * buyer's {@code purchases_count} up by one; for a consignment car with a partner, the car's
	 * commission added to the partner's {@code total_commission}, its {@code cars_count} up by one and
	 * a {@code comissao_venda} sale transaction for the commission; otherwise a {@code venda_veiculo}
	 * sale transaction for the sale price. All in this transaction (the hooks join it).
	 *
	 * <p>Locks: the car (then {@link CarAlreadySoldException} if sold, before anything else), the
	 * partner, the client. A refused sale (404, 409) changes nothing.
	 *
	 * @param id the car's id
	 * @param request the sale price and, optionally, the buyer's id
	 * @return the sold car
	 * @throws ResourceNotFoundException if the car or the client does not exist (mapped to 404)
	 * @throws CarAlreadySoldException if the car is already sold (mapped to 409)
	 */
	@Transactional
	public BackOfficeCarResponse sell(UUID id, SellCarRequest request) {
		Car car = lockCar(id);
		if (car.isVendido()) {
			throw new CarAlreadySoldException(id);
		}
		UUID partnerId = consignmentPartnerId(car);
		Partner partner = partnerId != null ? lockPartner(partnerId) : null;
		Client buyer = request.clienteId() != null ? lockClient(request.clienteId()) : null;

		car.setVendido(true);
		car.setPrecoVenda(request.precoVenda());
		car.setDataVenda(OffsetDateTime.now());
		car.setClient(buyer);
		if (buyer != null) {
			buyer.setPurchasesCount(buyer.getPurchasesCount() + 1);
		}
		String carName = car.getMarca() + " " + car.getModelo();
		if (partner != null) {
			partnerService.registerCommission(partner, car.getCommissionValue());
			partnerService.incrementCarsCount(partner);
			BigDecimal commission = Objects.requireNonNullElse(car.getCommissionValue(), BigDecimal.ZERO);
			transactionService.createSaleTransaction(car, commission, "Comissao: " + carName, "comissao_venda", partner);
		} else {
			transactionService.createSaleTransaction(car, request.precoVenda(), "Venda: " + carName, "venda_veiculo", null);
		}
		return BackOfficeCarResponse.from(carRepository.saveAndFlush(car));
	}

	/**
	 * Reverts a sale: the buyer's {@code purchases_count} down by one (never below 0); for a
	 * consignment car with a partner, the car's commission and one car back out of the partner's
	 * counters; only the system-generated sale transaction of the car is deleted (a manual one never
	 * is); {@code vendido}, {@code precoVenda}, {@code dataVenda} and the buyer cleared.
	 *
	 * <p>On a car that is not sold this is a no-op that still answers 200: before this guard {@code
	 * dcbo-backend} discounted a partner's commission for a sale that never happened, also on a
	 * second revert. Locks: the car, the partner, the client.
	 *
	 * @param id the car's id
	 * @return the car, unchanged if it was not sold
	 * @throws ResourceNotFoundException if no car has this id (mapped to 404)
	 */
	@Transactional
	public BackOfficeCarResponse revertSale(UUID id) {
		Car car = lockCar(id);
		if (!car.isVendido()) {
			return BackOfficeCarResponse.from(car);
		}
		UUID partnerId = consignmentPartnerId(car);
		Partner partner = partnerId != null ? lockPartner(partnerId) : null;
		Client buyer = car.getClient() != null ? lockClient(car.getClient().getId()) : null;

		if (buyer != null) {
			decrementPurchases(buyer);
		}
		if (partner != null) {
			partnerService.reverseCommission(partner, car.getCommissionValue());
			partnerService.decrementCarsCount(partner);
		}
		transactionService.deleteSaleTransactions(id);
		car.setVendido(false);
		car.setPrecoVenda(null);
		car.setDataVenda(null);
		car.setClient(null);
		return BackOfficeCarResponse.from(carRepository.saveAndFlush(car));
	}

	/**
	 * Reserves a car. Idempotent: reserving a reserved car changes nothing. The car's row is locked
	 * first: an unlocked reservation running next to a sale wrote {@code vendido = false} back.
	 *
	 * @param id the car's id
	 * @return the car
	 * @throws ResourceNotFoundException if no car has this id (mapped to 404)
	 */
	@Transactional
	public BackOfficeCarResponse reserve(UUID id) {
		Car car = lockCar(id);
		car.setReservado(true);
		return BackOfficeCarResponse.from(carRepository.saveAndFlush(car));
	}

	/**
	 * Releases a car's reservation. Idempotent: releasing a car that is not reserved changes
	 * nothing. The car's row is locked first, as in {@link #reserve}.
	 *
	 * @param id the car's id
	 * @return the car
	 * @throws ResourceNotFoundException if no car has this id (mapped to 404)
	 */
	@Transactional
	public BackOfficeCarResponse releaseReservation(UUID id) {
		Car car = lockCar(id);
		car.setReservado(false);
		return BackOfficeCarResponse.from(carRepository.saveAndFlush(car));
	}

	/**
	 * Features a car or removes it from the featured set, enforcing the 8-car limit. Removing frees
	 * a slot; featuring an already featured car does not count against the limit. The car's row is
	 * locked before the advisory lock (the lock order of this class).
	 *
	 * @param id the car's id
	 * @param request the requested state ({@code destaque} is never null after validation)
	 * @return the car
	 * @throws ResourceNotFoundException if no car has this id (mapped to 404)
	 * @throws HighlightLimitExceededException if featuring it would exceed the limit (mapped to 409)
	 */
	@Transactional
	public BackOfficeCarResponse highlight(UUID id, HighlightRequest request) {
		Car car = lockCar(id);
		boolean destaque = Boolean.TRUE.equals(request.destaque());
		assertHighlightLimitRespected(car.isDestaque(), destaque);
		car.setDestaque(destaque);
		return BackOfficeCarResponse.from(carRepository.saveAndFlush(car));
	}

	/**
	 * Refuses to feature one more car once {@link #HIGHLIGHT_LIMIT} are featured. Only when the
	 * request turns the highlight on for a car that does not have it, it first takes the advisory
	 * lock ({@link CarRepository#lockHighlightSlots()}), then counts: concurrent requests are
	 * serialised and each count sees the highlight the previous one committed (AC E.2). Must run
	 * before any write of the calling transaction, which is what rules out a deadlock between two
	 * holders.
	 *
	 * @param alreadyFeatured whether the car is featured today ({@code false} for a new car)
	 * @param requestedDestaque the state the request asks for
	 * @throws HighlightLimitExceededException if the limit is already reached
	 */
	private void assertHighlightLimitRespected(boolean alreadyFeatured, boolean requestedDestaque) {
		if (!requestedDestaque || alreadyFeatured) {
			return;
		}
		carRepository.lockHighlightSlots();
		if (carRepository.countByDestaqueTrue() >= HIGHLIGHT_LIMIT) {
			throw new HighlightLimitExceededException(HIGHLIGHT_LIMIT);
		}
	}

	/**
	 * Moves what a sale registered on a partner from the partner/commission the sold car had before
	 * the edit to the ones it has now: reverse on the previous one (if the car was in consignment
	 * with a partner), register on the new one (if it is). Both may be the same partner (only the
	 * commission changed); the same managed instance then receives both calls.
	 *
	 * @param registeredPartner the partner the sale registered on, locked, or {@code null}
	 * @param registeredCommission the commission value the car had before the edit
	 * @param car the car with the edit already applied
	 */
	private void reconcileConsignmentSaleAfterEdit(
			Partner registeredPartner, BigDecimal registeredCommission, Car car) {
		if (registeredPartner != null) {
			partnerService.reverseCommission(registeredPartner, registeredCommission);
			partnerService.decrementCarsCount(registeredPartner);
		}
		if (car.isConsignacao() && car.getPartner() != null) {
			partnerService.registerCommission(car.getPartner(), car.getCommissionValue());
			partnerService.incrementCarsCount(car.getPartner());
		}
	}

	private static void applyUpdate(Car car, CarRequest request, boolean destaque, Partner partner) {
		car.setMarca(request.marca());
		car.setModelo(request.modelo());
		car.setAno(request.ano());
		car.setPreco(request.preco());
		car.setKm(request.km());
		car.setCor(request.cor());
		car.setCombustivel(request.combustivel());
		car.setTransmissao(request.transmissao());
		car.setOrigem(request.origem());
		car.setDescricao(request.descricao());
		if (request.observacoes() != null) {
			car.setObservacoes(blankToNull(request.observacoes()));
		}
		car.setPrecoCompra(request.precoCompra());
		car.setDataCompra(request.dataCompra());
		car.setConsignacao(Boolean.TRUE.equals(request.isConsignacao()));
		car.setPartner(partner);
		car.setCommissionValue(request.commissionValue());
		car.setGarantiaMeses(request.garantiaMeses() != null ? request.garantiaMeses() : 0);
		car.setDestaque(destaque);
		if (request.images() != null) {
			for (CarImage existing : new ArrayList<>(car.getImages())) {
				car.removeImage(existing);
			}
			addImages(car, request.images(), request.imageThumbnails());
		}
	}

	/**
	 * The id of the partner a sale of this car registers (or registered) a commission on: the car's
	 * partner if it is in consignment, else {@code null}. Reads only the id of the {@code LAZY} proxy,
	 * which does not load the partner, so the lock that follows is still its first load.
	 */
	private static UUID consignmentPartnerId(Car car) {
		return car.isConsignacao() && car.getPartner() != null ? car.getPartner().getId() : null;
	}

	/**
	 * Locks the given partners (nulls and duplicates ignored) in {@link #LOCK_ORDER}, the order every
	 * transaction that locks two partners uses.
	 *
	 * @return the locked partners by id
	 * @throws ResourceNotFoundException if one of them does not exist (mapped to 404)
	 */
	private Map<UUID, Partner> lockPartners(UUID... ids) {
		Map<UUID, Partner> locked = new TreeMap<>(LOCK_ORDER);
		for (UUID partnerId : ids) {
			if (partnerId != null) {
				locked.put(partnerId, null);
			}
		}
		locked.replaceAll((partnerId, ignored) -> lockPartner(partnerId));
		return locked;
	}

	private Car lockCar(UUID id) {
		return carRepository.findByIdForUpdate(id).orElseThrow(() -> carNotFound(id));
	}

	private Partner lockPartner(UUID partnerId) {
		return partnerRepository.findByIdForUpdate(partnerId).orElseThrow(() -> partnerNotFound(partnerId));
	}

	private Client lockClient(UUID clientId) {
		return clientRepository
				.findByIdForUpdate(clientId)
				.orElseThrow(() -> new ResourceNotFoundException("Cliente nao encontrado: " + clientId));
	}

	/**
	 * Looks a partner up with {@code findById}, never {@code getReferenceById}: a reference to an id
	 * that does not exist only fails at flush, as a foreign key violation (409), instead of the 404 a
	 * caller-supplied id deserves. No lock: used where the partner's counters do not change.
	 */
	private Partner requirePartner(UUID partnerId) {
		return partnerRepository.findById(partnerId).orElseThrow(() -> partnerNotFound(partnerId));
	}

	private static ResourceNotFoundException carNotFound(UUID id) {
		return new ResourceNotFoundException("Carro nao encontrado: " + id);
	}

	private static ResourceNotFoundException partnerNotFound(UUID partnerId) {
		return new ResourceNotFoundException("Parceiro nao encontrado: " + partnerId);
	}

	private static void decrementPurchases(Client client) {
		client.setPurchasesCount(Math.max(0, client.getPurchasesCount() - 1));
	}

	private static void requireSortable(Sort sort) {
		for (Sort.Order order : sort) {
			if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
				throw new UnknownSortPropertyException(order.getProperty());
			}
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static void addImages(Car car, List<String> urls, List<String> thumbnails) {
		List<String> effectiveUrls = urls != null ? urls : List.of();
		List<String> effectiveThumbnails = thumbnails != null ? thumbnails : List.of();
		for (int position = 0; position < effectiveUrls.size(); position++) {
			car.addImage(CarImage.builder()
					.url(effectiveUrls.get(position))
					.thumbnailUrl(position < effectiveThumbnails.size() ? effectiveThumbnails.get(position) : null)
					.position(position)
					.build());
		}
	}
}
