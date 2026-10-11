package pt.diamondcars.catalogbackend.web.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import pt.diamondcars.catalogbackend.domain.client.Client;

/**
 * Response payload for every {@code /api/backoffice/clients} endpoint (TASK-004), the read-side
 * counterpart of {@link ClientRequest}. Never the JPA entity itself (mirrors {@link
 * BackOfficeCarResponse}), ported unchanged from {@code dcbo-backend}'s {@code ClientResponse}.
 *
 * @param id the client's identifier
 * @param name full name
 * @param email email address, or {@code null}/blank if not given
 * @param phone phone number
 * @param nif tax id, or {@code null}/blank if not given
 * @param address postal address, or {@code null}/blank if not given
 * @param postalCode postal code, or {@code null}/blank if not given
 * @param notes free-text notes, or {@code null}/blank if not given
 * @param purchasesCount number of cars this client has purchased; never written directly by this
 *     API's caller
 * @param createdAt creation timestamp
 * @param updatedAt last-update timestamp
 */
public record ClientResponse(
		UUID id,
		String name,
		String email,
		String phone,
		String nif,
		String address,
		String postalCode,
		String notes,
		int purchasesCount,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	/**
	 * Builds the response for a given, fully-loaded {@link Client}.
	 *
	 * @param client the client to map, never {@code null}
	 * @return the corresponding response DTO
	 */
	public static ClientResponse from(Client client) {
		return new ClientResponse(
				client.getId(),
				client.getName(),
				client.getEmail(),
				client.getPhone(),
				client.getNif(),
				client.getAddress(),
				client.getPostalCode(),
				client.getNotes(),
				client.getPurchasesCount(),
				client.getCreatedAt(),
				client.getUpdatedAt());
	}
}
