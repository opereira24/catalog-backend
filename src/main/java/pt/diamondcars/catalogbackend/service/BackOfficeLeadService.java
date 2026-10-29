package pt.diamondcars.catalogbackend.service;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.lead.LeadOrigin;
import pt.diamondcars.catalogbackend.domain.lead.LeadRepository;
import pt.diamondcars.catalogbackend.domain.lead.LeadStatus;
import pt.diamondcars.catalogbackend.web.dto.BackOfficeLeadRequest;
import pt.diamondcars.catalogbackend.web.dto.BackOfficeLeadResponse;
import pt.diamondcars.catalogbackend.web.exception.ResourceNotFoundException;
import pt.diamondcars.catalogbackend.web.exception.UnknownSortPropertyException;

/**
 * Business logic of {@code /api/backoffice/leads} (TASK-006, AC E.2), ported from {@code
 * dcbo-backend}'s {@code LeadService} ({@code getLeads}, {@code addLead}, {@code updateLead}, {@code
 * deleteLead} of {@code dcbo/src/services/firebaseService.js}). The public twin is {@link
 * LeadService}, which only creates leads from the site.
 *
 * <p>Read-only transaction by default; every write method is {@code @Transactional}; every method maps
 * to a DTO before returning ({@code spring.jpa.open-in-view: false}).
 *
 * <p>Concurrency, each rule with its test in {@code BackOfficeLeadConcurrencyTest}:
 *
 * <ul>
 *   <li>{@link #update} and {@link #delete} read the lead with {@link LeadRepository#findByIdForUpdate}
 *       as its first load in the transaction. Without the row lock, either of them racing a {@code
 *       DELETE} of the same lead touched 0 rows at flush ({@code StaleStateException}, 500).
 *   <li>{@link #create} resolves the car with {@link CarRepository#lockKeyShare}, as {@code POST
 *       /api/leads} does (K8): a car deleted between the lookup and the {@code INSERT} would fail the
 *       foreign key.
 *   <li>The car of a lead never changes after creation ({@code Lead.car} is {@code updatable =
 *       false}), so an edit never writes back a {@code car_id} that a car deletion cleared.
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class BackOfficeLeadService {

	/**
	 * Properties a listing may sort by: only the scalar columns of {@link Lead}, which has the {@code
	 * LAZY} association {@code car} ({@code sort=car.marca} would be a join; {@code web/CLAUDE.md}).
	 * {@code status} and {@code origem} sort by the stored text.
	 */
	static final Set<String> SORTABLE_PROPERTIES = Set.of(
			"nome", "email", "telefone", "status", "origem", "followUpDate", "carroMarca", "carroModelo",
			"carroPreco", "createdAt", "updatedAt", "id");

	private final LeadRepository leadRepository;
	private final CarRepository carRepository;

	/**
	 * Creates the service with its dependencies.
	 *
	 * @param leadRepository persistence for {@link Lead}
	 * @param carRepository persistence for {@link Car}, to resolve the {@code carroId} of a new lead
	 */
	public BackOfficeLeadService(LeadRepository leadRepository, CarRepository carRepository) {
		this.leadRepository = leadRepository;
		this.carRepository = carRepository;
	}

	/**
	 * Lists leads, optionally filtered by status and car.
	 *
	 * @param status required status, or {@code null} to not filter by it
	 * @param carroId required car, or {@code null} to not filter by it
	 * @param pageable pagination and sorting (the controller defaults to {@code createdAt} DESC, 50 per
	 *     page); {@code id} DESC is appended when the sort lacks it ({@link
	 *     StableSort#withIdTiebreak}), after the allow-list check
	 * @return the requested page
	 * @throws UnknownSortPropertyException if a sort property is outside {@link #SORTABLE_PROPERTIES}
	 *     (mapped to 400), checked before any query
	 */
	public Page<BackOfficeLeadResponse> list(LeadStatus status, UUID carroId, Pageable pageable) {
		requireSortable(pageable.getSort());
		return leadRepository
				.findAll(BackOfficeLeadSpecifications.matching(status, carroId), StableSort.withIdTiebreak(pageable))
				.map(BackOfficeLeadResponse::from);
	}

	/**
	 * Fetches a single lead.
	 *
	 * @param id the lead's id
	 * @return the matching lead
	 * @throws ResourceNotFoundException if no lead has this id (mapped to 404)
	 */
	public BackOfficeLeadResponse get(UUID id) {
		return BackOfficeLeadResponse.from(leadRepository.findById(id).orElseThrow(() -> leadNotFound(id)));
	}

	/**
	 * Creates a lead in the back-office ({@code addLead} of {@code dcbo}): {@code origem} is always
	 * {@code backoffice}; contact, status, follow-up, notes and the car snapshot come from the body.
	 * No notification (parity: only a lead of the site raises one).
	 *
	 * @param request the validated payload
	 * @return the created lead
	 * @throws ResourceNotFoundException if {@code carroId} is given and no car has it (mapped to 404)
	 */
	@Transactional
	public BackOfficeLeadResponse create(BackOfficeLeadRequest request) {
		Lead lead = new Lead();
		applyEditableFields(lead, request);
		lead.setCar(request.carroId() != null ? lockCar(request.carroId()) : null);
		lead.setCarroMarca(request.carroMarca());
		lead.setCarroModelo(request.carroModelo());
		lead.setCarroPreco(request.carroPreco());
		lead.setOrigem(LeadOrigin.BACKOFFICE);
		return BackOfficeLeadResponse.from(leadRepository.saveAndFlush(lead));
	}

	/**
	 * Replaces the 6 editable fields of a lead ({@code updateLead} of {@code dcbo} always sends the
	 * 6): {@code nome}, {@code telefone}, {@code email}, {@code notas}, {@code status}, {@code
	 * followUpDate}. A {@code null} or absent one clears it. Never changes the car, its snapshot,
	 * {@code mensagem} or {@code origem}.
	 *
	 * @param id the lead's id
	 * @param request the validated payload (car fields ignored)
	 * @return the updated lead
	 * @throws ResourceNotFoundException if no lead has this id, also when a concurrent {@code DELETE}
	 *     of it committed first (mapped to 404)
	 */
	@Transactional
	public BackOfficeLeadResponse update(UUID id, BackOfficeLeadRequest request) {
		Lead lead = lockLead(id);
		applyEditableFields(lead, request);
		return BackOfficeLeadResponse.from(leadRepository.saveAndFlush(lead));
	}

	/**
	 * Deletes a lead; its notifications go with it ({@code ON DELETE CASCADE} of {@code
	 * notifications.lead_id}).
	 *
	 * @param id the lead's id
	 * @throws ResourceNotFoundException if no lead has this id, also when a concurrent {@code DELETE}
	 *     of it committed first (mapped to 404)
	 */
	@Transactional
	public void delete(UUID id) {
		leadRepository.delete(lockLead(id));
	}

	private static void applyEditableFields(Lead lead, BackOfficeLeadRequest request) {
		lead.setNome(request.nome());
		lead.setTelefone(request.telefone());
		lead.setEmail(request.email());
		lead.setNotas(request.notas());
		lead.setStatus(LeadStatus.fromValue(request.status()));
		lead.setFollowUpDate(request.followUpDate());
	}

	private Lead lockLead(UUID id) {
		return leadRepository.findByIdForUpdate(id).orElseThrow(() -> leadNotFound(id));
	}

	private Car lockCar(UUID carId) {
		return carRepository
				.lockKeyShare(carId)
				.map(carRepository::getReferenceById)
				.orElseThrow(() -> new ResourceNotFoundException("Carro nao encontrado: " + carId));
	}

	private static void requireSortable(Sort sort) {
		for (Sort.Order order : sort) {
			if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
				throw new UnknownSortPropertyException(order.getProperty());
			}
		}
	}

	private static ResourceNotFoundException leadNotFound(UUID id) {
		return new ResourceNotFoundException("Lead nao encontrado: " + id);
	}
}
