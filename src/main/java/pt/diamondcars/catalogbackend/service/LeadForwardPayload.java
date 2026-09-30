package pt.diamondcars.catalogbackend.service;

import java.util.UUID;
import pt.diamondcars.catalogbackend.domain.lead.Lead;

/**
 * JSON payload {@link LeadForwarder} posts to {@code dcbo-backend}'s {@code POST /internal/leads}
 * (TASK-015 requirement 5), field-for-field identical to that endpoint's own request DTO ({@code
 * dcbo-backend}'s {@code web/dto/InternalLeadRequest.java}) so the two services' contracts stay in
 * sync without either module depending on the other's code.
 *
 * @param nome full name
 * @param email contact email, or {@code null}
 * @param telefone contact phone number
 * @param mensagem free-text message, or {@code null}
 * @param carroId id of the car this lead is about, or {@code null}
 * @param carroMarca denormalized brand snapshot of {@link #carroId}, or {@code null}
 * @param carroModelo denormalized model snapshot of {@link #carroId}, or {@code null}
 * @param origem {@code "website"} or {@code "website-contacto"}
 */
record LeadForwardPayload(
		String nome,
		String email,
		String telefone,
		String mensagem,
		UUID carroId,
		String carroMarca,
		String carroModelo,
		String origem) {

	/**
	 * Builds the payload from a persisted {@link Lead}.
	 *
	 * @param lead the lead to forward, never {@code null}
	 * @return the corresponding payload
	 */
	static LeadForwardPayload from(Lead lead) {
		return new LeadForwardPayload(
				lead.getNome(),
				lead.getEmail(),
				lead.getTelefone(),
				lead.getMensagem(),
				lead.getCarId(),
				lead.getCarroMarca(),
				lead.getCarroModelo(),
				lead.getOrigem().getValue());
	}
}
