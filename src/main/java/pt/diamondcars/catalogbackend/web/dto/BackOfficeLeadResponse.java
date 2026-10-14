package pt.diamondcars.catalogbackend.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import pt.diamondcars.catalogbackend.domain.lead.Lead;

/**
 * The full lead as the back-office sees it, returned by every {@code /api/backoffice/leads}
 * endpoint (TASK-006, AC E.2): exactly 15 keys, the same as {@code dcbo-backend}'s {@code
 * LeadResponse}. The public {@code POST /api/leads} keeps its own {@link LeadResponse} (only the
 * id).
 *
 * <p>{@link #carroId} keeps the JSON name {@code dcbo} writes over the English association {@code
 * car}/{@code car_id} (ADR-001, layer 3). Both {@link #mensagem} (what the visitor wrote on the site)
 * and {@link #notas} (the staff's notes) are here: the site's message lives in {@code mensagem}
 * (TASK-006 AC C.2).
 *
 * @param id the lead's identifier
 * @param nome full name
 * @param email email address, or {@code null}
 * @param telefone phone number
 * @param mensagem message written on the public site, or {@code null}
 * @param notas notes of the stand's staff, or {@code null}
 * @param carroId the car the lead is about, or {@code null} (none, or since deleted)
 * @param carroMarca brand snapshot at creation, or {@code null}
 * @param carroModelo model snapshot at creation, or {@code null}
 * @param carroPreco price snapshot at creation, or {@code null}
 * @param followUpDate date of the next follow-up, or {@code null}
 * @param status stored value of the pipeline status, e.g. {@code ativo}
 * @param origem stored value of the origin: {@code website}, {@code website-contacto} or {@code
 *     backoffice}
 * @param createdAt creation timestamp
 * @param updatedAt last-update timestamp
 */
public record BackOfficeLeadResponse(
		UUID id,
		String nome,
		String email,
		String telefone,
		String mensagem,
		String notas,
		UUID carroId,
		String carroMarca,
		String carroModelo,
		BigDecimal carroPreco,
		LocalDate followUpDate,
		String status,
		String origem,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	/**
	 * Builds the response for a {@link Lead}. Call only inside the {@code @Transactional} service
	 * method that loaded it.
	 *
	 * <p>Reads only {@code getId()} of the {@code LAZY} car proxy, which Hibernate answers without
	 * loading the car: listing 50 leads costs the page and {@code count} queries, never one query per
	 * car ({@code BackOfficeLeadControllerTest}). Also safe outside a transaction for the same reason.
	 *
	 * @param lead the lead to map, never {@code null}
	 * @return the corresponding response DTO
	 */
	public static BackOfficeLeadResponse from(Lead lead) {
		return new BackOfficeLeadResponse(
				lead.getId(),
				lead.getNome(),
				lead.getEmail(),
				lead.getTelefone(),
				lead.getMensagem(),
				lead.getNotas(),
				lead.getCar() != null ? lead.getCar().getId() : null,
				lead.getCarroMarca(),
				lead.getCarroModelo(),
				lead.getCarroPreco(),
				lead.getFollowUpDate(),
				lead.getStatus().getValue(),
				lead.getOrigem().getValue(),
				lead.getCreatedAt(),
				lead.getUpdatedAt());
	}
}
