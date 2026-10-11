package pt.diamondcars.catalogbackend.service;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.domain.client.Client;
import pt.diamondcars.catalogbackend.domain.client.ClientRepository;
import pt.diamondcars.catalogbackend.web.dto.BackOfficeCarResponse;
import pt.diamondcars.catalogbackend.web.dto.ClientRequest;
import pt.diamondcars.catalogbackend.web.dto.ClientResponse;
import pt.diamondcars.catalogbackend.web.exception.ResourceInUseException;
import pt.diamondcars.catalogbackend.web.exception.ResourceNotFoundException;

/**
 * Business logic for the whole {@code /api/backoffice/clients} surface (TASK-004), ported from
 * {@code dcbo-backend}'s {@code ClientService}.
 *
 * <p>Every public method is {@code @Transactional} and maps its result to a {@link
 * ClientResponse}/{@link BackOfficeCarResponse} before returning, never handing the {@link
 * Client} entity itself back to the caller: with {@code spring.jpa.open-in-view: false}, mapping
 * must happen while the persistence context is open.
 */
@Service
public class ClientService {

	private final ClientRepository clientRepository;
	private final CarRepository carRepository;

	/**
	 * Creates the service with its collaborating repositories.
	 *
	 * @param clientRepository persistence for {@link Client}
	 * @param carRepository persistence for {@link pt.diamondcars.catalogbackend.domain.car.Car},
	 *     needed to list a client's purchased cars and to refuse deleting a client that has some
	 */
	public ClientService(ClientRepository clientRepository, CarRepository carRepository) {
		this.clientRepository = clientRepository;
		this.carRepository = carRepository;
	}

	/**
	 * Lists clients, most recently created first by default, optionally filtered by a free-text
	 * search over name/email/phone/NIF.
	 *
	 * @param search free text to search for, or {@code null}/blank to not filter at all
	 * @param pageable pagination/sorting, defaulted by the controller to 50 per page sorted by
	 *     {@code createdAt} descending
	 * @return the requested page, mapped to {@link ClientResponse}
	 */
	@Transactional(readOnly = true)
	public Page<ClientResponse> list(String search, Pageable pageable) {
		return clientRepository
				.findAll(ClientSpecifications.matching(search), pageable)
				.map(ClientResponse::from);
	}

	/**
	 * Fetches a single client.
	 *
	 * @param id the client's id
	 * @return the matching client
	 * @throws ResourceNotFoundException if no client has this id (mapped to 404)
	 */
	@Transactional(readOnly = true)
	public ClientResponse get(UUID id) {
		return ClientResponse.from(findOrThrow(id));
	}

	/**
	 * Creates a new client.
	 *
	 * @param request the validated payload
	 * @return the created client
	 */
	@Transactional
	public ClientResponse create(ClientRequest request) {
		Client client = new Client();
		applyRequest(client, request);
		return ClientResponse.from(clientRepository.save(client));
	}

	/**
	 * Updates an existing client, replacing every field with the given payload. Never touches
	 * {@link Client#getPurchasesCount()}, which is only ever changed server-side on sale/reversal.
	 *
	 * @param id the client's id
	 * @param request the validated payload
	 * @return the updated client
	 * @throws ResourceNotFoundException if no client has this id (mapped to 404)
	 */
	@Transactional
	public ClientResponse update(UUID id, ClientRequest request) {
		Client client = findOrThrow(id);
		applyRequest(client, request);
		return ClientResponse.from(client);
	}

	/**
	 * Deletes a client, refusing when it still has purchased cars associated rather than deleting
	 * it silently and orphaning those cars' {@code client_id}.
	 *
	 * @param id the client's id
	 * @throws ResourceNotFoundException if no client has this id (mapped to 404)
	 * @throws ResourceInUseException if at least one car still references this client (mapped to
	 *     409)
	 */
	@Transactional
	public void delete(UUID id) {
		Client client = findOrThrow(id);
		if (carRepository.existsByClientId(id)) {
			throw new ResourceInUseException("Cliente tem carros associados: " + id);
		}
		clientRepository.delete(client);
	}

	/**
	 * Lists the cars purchased by a client, most recently created first.
	 *
	 * @param id the client's id
	 * @return the matching cars, mapped to {@link BackOfficeCarResponse}
	 * @throws ResourceNotFoundException if no client has this id (mapped to 404)
	 */
	@Transactional(readOnly = true)
	public List<BackOfficeCarResponse> cars(UUID id) {
		findOrThrow(id);
		return carRepository.findByClientIdOrderByCreatedAtDesc(id).stream()
				.map(BackOfficeCarResponse::from)
				.toList();
	}

	private Client findOrThrow(UUID id) {
		return clientRepository
				.findById(id)
				.orElseThrow(() -> new ResourceNotFoundException("Cliente nao encontrado: " + id));
	}

	private void applyRequest(Client client, ClientRequest request) {
		client.setName(request.name());
		client.setEmail(request.email());
		client.setPhone(request.phone());
		client.setNif(request.nif());
		client.setAddress(request.address());
		client.setPostalCode(request.postalCode());
		client.setNotes(request.notes());
	}
}
