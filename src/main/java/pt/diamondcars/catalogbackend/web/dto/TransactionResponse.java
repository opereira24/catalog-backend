package pt.diamondcars.catalogbackend.web.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import pt.diamondcars.catalogbackend.domain.transaction.Transaction;

/**
 * Response payload of every {@code /api/backoffice/transactions} endpoint (TASK-005, AC B.2),
 * exactly 12 JSON keys. Never the JPA entity itself. Ported from {@code dcbo-backend}.
 *
 * <p>{@link #carroId}/{@link #clienteId} keep the JSON names {@code dcbo} already writes while the
 * columns and associations are English ({@code car_id}/{@link Transaction#getCar()}, {@code
 * client_id}/{@link Transaction#getClient()}): ADR-001, layer 3. {@link #partnerId} is the same in
 * both layers.
 *
 * @param id the transaction's identifier
 * @param tipo lower-case value of the kind: {@code compra}, {@code venda}, {@code receita} or {@code
 *     despesa}
 * @param valor the amount, always strictly positive
 * @param descricao free-text description
 * @param categoria free-text category tag, or {@code null}
 * @param data calendar date of the transaction, serialised as {@code "YYYY-MM-DD"}
 * @param carroId the car this transaction is about, or {@code null}
 * @param clienteId the client involved (the buyer of a sale), or {@code null}
 * @param partnerId the consignment partner involved, or {@code null}
 * @param systemGenerated whether a car sale created this transaction, rather than a person
 * @param createdAt creation timestamp
 * @param updatedAt last-update timestamp
 */
public record TransactionResponse(
		UUID id,
		String tipo,
		BigDecimal valor,
		String descricao,
		String categoria,
		LocalDate data,
		UUID carroId,
		UUID clienteId,
		UUID partnerId,
		boolean systemGenerated,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	/**
	 * Builds the response for a {@link Transaction}. Call only inside the {@code @Transactional}
	 * service method that loaded it.
	 *
	 * <p>Reads only {@code getId()} of the {@code LAZY} car, client and partner proxies, which
	 * Hibernate answers without loading them: listing 100 transactions costs the page and {@code
	 * count} queries, never one query per association (TASK-005 AC A.2, fixed by {@code
	 * TransactionControllerTest}). Reading any other field of an association here brings back the N+1.
	 *
	 * @param transaction the transaction to map, never {@code null}
	 * @return the corresponding response DTO
	 */
	public static TransactionResponse from(Transaction transaction) {
		return new TransactionResponse(
				transaction.getId(),
				transaction.getTipo().getValue(),
				transaction.getValor(),
				transaction.getDescricao(),
				transaction.getCategoria(),
				transaction.getData(),
				transaction.getCar() != null ? transaction.getCar().getId() : null,
				transaction.getClient() != null ? transaction.getClient().getId() : null,
				transaction.getPartner() != null ? transaction.getPartner().getId() : null,
				transaction.isSystemGenerated(),
				transaction.getCreatedAt(),
				transaction.getUpdatedAt());
	}
}
