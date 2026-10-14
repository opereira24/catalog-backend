package pt.diamondcars.catalogbackend.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.partner.Partner;
import pt.diamondcars.catalogbackend.domain.partner.PartnerRepository;
import pt.diamondcars.catalogbackend.web.dto.BackOfficeCarResponse;
import pt.diamondcars.catalogbackend.web.dto.PartnerRequest;
import pt.diamondcars.catalogbackend.web.dto.PartnerResponse;
import pt.diamondcars.catalogbackend.web.exception.ResourceInUseException;
import pt.diamondcars.catalogbackend.web.exception.ResourceNotFoundException;

/**
 * Business logic for the whole {@code /api/backoffice/partners} surface (TASK-004), ported from
 * {@code dcbo-backend}'s {@code PartnerService}, plus the {@link #incrementCarsCount}/{@link
 * #decrementCarsCount}/{@link #registerCommission}/{@link #reverseCommission} hooks {@code
 * BackOfficeCarService} calls on sale, reversal and edit of a sold consignment car (TASK-010) to
 * keep {@link Partner#getCarsCount()}/{@link Partner#getTotalCommission()} in sync server-side. The
 * caller hands them a partner it loaded with {@link PartnerRepository#findByIdForUpdate} (row
 * locked); {@code PartnerServiceCountersTest} proves each hook in isolation.
 *
 * <p>Both counters are only ever touched by a sale, its reversal, and the edit of an already sold
 * consignment car (which moves what the sale registered); never by a car's creation, deletion, or
 * the edit of an unsold car (TASK-003 AC G.1 and TASK-010 AC B.3/C.2).
 *
 * <p>Every public method is {@code @Transactional} and maps its result to a {@link
 * PartnerResponse}/{@link BackOfficeCarResponse} before returning: with {@code
 * spring.jpa.open-in-view: false}, mapping must happen while the persistence context is open.
 */
@Service
public class PartnerService {

	private final PartnerRepository partnerRepository;
	private final CarRepository carRepository;

	/**
	 * Creates the service with its collaborating repositories.
	 *
	 * @param partnerRepository persistence for {@link Partner}
	 * @param carRepository persistence for {@link pt.diamondcars.catalogbackend.domain.car.Car},
	 *     needed to list a partner's consignment cars and to refuse deleting a partner that still
	 *     has some
	 */
	public PartnerService(PartnerRepository partnerRepository, CarRepository carRepository) {
		this.partnerRepository = partnerRepository;
		this.carRepository = carRepository;
	}

	/**
	 * Lists partners, most recently created first by default.
	 *
	 * @param pageable pagination/sorting, defaulted by the controller to 50 per page sorted by
	 *     {@code createdAt} descending; {@code id} DESC is appended when the sort lacks it ({@link
	 *     StableSort#withIdTiebreak})
	 * @return the requested page, mapped to {@link PartnerResponse}
	 */
	@Transactional(readOnly = true)
	public Page<PartnerResponse> list(Pageable pageable) {
		return partnerRepository.findAll(StableSort.withIdTiebreak(pageable)).map(PartnerResponse::from);
	}

	/**
	 * Fetches a single partner.
	 *
	 * @param id the partner's id
	 * @return the matching partner
	 * @throws ResourceNotFoundException if no partner has this id (mapped to 404)
	 */
	@Transactional(readOnly = true)
	public PartnerResponse get(UUID id) {
		return PartnerResponse.from(findOrThrow(id));
	}

	/**
	 * Creates a new partner.
	 *
	 * @param request the validated payload
	 * @return the created partner
	 */
	@Transactional
	public PartnerResponse create(PartnerRequest request) {
		Partner partner = new Partner();
		applyRequest(partner, request);
		return PartnerResponse.from(partnerRepository.save(partner));
	}

	/**
	 * Updates an existing partner, replacing every field with the given payload. Never touches
	 * {@link Partner#getCarsCount()}/{@link Partner#getTotalCommission()}, which are only ever
	 * changed by the hooks below.
	 *
	 * <p>Reads the partner with its row locked ({@link PartnerRepository#findByIdForUpdate}, TASK-010
	 * AC C.1.b): Hibernate writes the whole row, counters included, with the values it read, so an
	 * unlocked edit running next to a sale wrote the old {@code cars_count} back (measured: 0 instead
	 * of 1).
	 *
	 * @param id the partner's id
	 * @param request the validated payload
	 * @return the updated partner
	 * @throws ResourceNotFoundException if no partner has this id (mapped to 404)
	 */
	@Transactional
	public PartnerResponse update(UUID id, PartnerRequest request) {
		Partner partner = partnerRepository
				.findByIdForUpdate(id)
				.orElseThrow(() -> partnerNotFound(id));
		applyRequest(partner, request);
		return PartnerResponse.from(partner);
	}

	/**
	 * Deletes a partner, refusing when it still has consignment cars associated rather than
	 * deleting it silently and orphaning those cars' {@code partner_id}.
	 *
	 * <p>Order: lock the partner's row with {@code FOR UPDATE} ({@link
	 * PartnerRepository#findByIdForDelete}, TASK-015), then check for cars, then {@code DELETE}. The
	 * lock conflicts with the {@code FOR KEY SHARE} PostgreSQL takes on this row while a concurrent
	 * {@code POST}/{@code PUT} of a car saves {@code partner_id} = this partner: if the car saved
	 * first, the lock waits for its commit and the check, a new statement in {@code READ COMMITTED},
	 * sees it (409); if the lock came first, the car's foreign key check waits and fails once this
	 * deletion commits (that request gets 409, nothing saved). Without the lock, or with checking
	 * before locking, both succeed and {@code ON DELETE SET NULL} leaves the car without partner.
	 *
	 * @param id the partner's id
	 * @throws ResourceNotFoundException if no partner has this id (mapped to 404)
	 * @throws ResourceInUseException if at least one car still references this partner (mapped to
	 *     409)
	 */
	@Transactional
	public void delete(UUID id) {
		Partner partner = partnerRepository
				.findByIdForDelete(id)
				.orElseThrow(() -> partnerNotFound(id));
		if (carRepository.existsByPartnerId(id)) {
			throw new ResourceInUseException("Parceiro tem carros de consignacao associados: " + id);
		}
		partnerRepository.delete(partner);
	}

	/**
	 * Lists the consignment cars attributed to a partner, most recently created first.
	 *
	 * @param id the partner's id
	 * @return the matching cars, mapped to {@link BackOfficeCarResponse}
	 * @throws ResourceNotFoundException if no partner has this id (mapped to 404)
	 */
	@Transactional(readOnly = true)
	public List<BackOfficeCarResponse> cars(UUID id) {
		findOrThrow(id);
		return carRepository.findByPartnerIdOrderByCreatedAtDesc(id).stream()
				.map(BackOfficeCarResponse::from)
				.toList();
	}

	/**
	 * Increments {@link Partner#getCarsCount()} by one, called by {@code BackOfficeCarService}
	 * (TASK-010) when a consignment car referencing this partner is sold.
	 *
	 * @param partner the partner to update, managed by the caller's persistence context; the
	 *     mutation is flushed by Hibernate's dirty checking at commit, no explicit save needed
	 */
	@Transactional
	public void incrementCarsCount(Partner partner) {
		partner.setCarsCount(partner.getCarsCount() + 1);
	}

	/**
	 * Decrements {@link Partner#getCarsCount()} by one (never below zero), called by {@code
	 * BackOfficeCarService} (TASK-010) when a consignment car's sale is reverted, or when an
	 * already-sold consignment car is re-assigned to a different partner via {@code PUT}.
	 *
	 * @param partner the partner to update, managed by the caller's persistence context
	 */
	@Transactional
	public void decrementCarsCount(Partner partner) {
		partner.setCarsCount(Math.max(0, partner.getCarsCount() - 1));
	}

	/**
	 * Adds a car's commission to {@link Partner#getTotalCommission()}, called by {@code
	 * BackOfficeCarService} (TASK-010) when a consignment car referencing this partner is sold.
	 *
	 * @param partner the partner to update, managed by the caller's persistence context
	 * @param commission the commission owed on this sale; the call is a no-op if {@code null}
	 */
	@Transactional
	public void registerCommission(Partner partner, BigDecimal commission) {
		if (commission == null) {
			return;
		}
		partner.setTotalCommission(partner.getTotalCommission().add(commission));
	}

	/**
	 * Subtracts a car's commission back out of {@link Partner#getTotalCommission()} (never below
	 * zero), called by {@code BackOfficeCarService} (TASK-010) when a consignment car's sale is
	 * reverted.
	 *
	 * @param partner the partner to update, managed by the caller's persistence context
	 * @param commission the commission to reverse; the call is a no-op if {@code null}
	 */
	@Transactional
	public void reverseCommission(Partner partner, BigDecimal commission) {
		if (commission == null) {
			return;
		}
		BigDecimal reversed = partner.getTotalCommission().subtract(commission);
		partner.setTotalCommission(reversed.max(BigDecimal.ZERO));
	}

	/**
	 * Builds the partner 404 ({@code Parceiro nao encontrado: <id>}), the only source of that message:
	 * also used by {@code BackOfficeCarService} when a car operation references an unknown partner.
	 *
	 * @param id the partner id that matched no row
	 * @return the exception to throw, mapped to 404 by {@code ApiExceptionHandler}
	 */
	static ResourceNotFoundException partnerNotFound(UUID id) {
		return new ResourceNotFoundException("Parceiro nao encontrado: " + id);
	}

	private Partner findOrThrow(UUID id) {
		return partnerRepository
				.findById(id)
				.orElseThrow(() -> partnerNotFound(id));
	}

	private void applyRequest(Partner partner, PartnerRequest request) {
		partner.setName(request.name());
		partner.setEmail(request.email());
		partner.setPhone(request.phone());
		partner.setNotes(request.notes());
	}
}
