package pt.diamondcars.catalogbackend.web.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import pt.diamondcars.catalogbackend.domain.partner.Partner;

/**
 * Response payload for every {@code /api/backoffice/partners} endpoint (TASK-004), the read-side
 * counterpart of {@link PartnerRequest}. Never the JPA entity itself (mirrors {@link
 * BackOfficeCarResponse}), ported unchanged from {@code dcbo-backend}'s {@code PartnerResponse}.
 *
 * @param id the partner's identifier
 * @param name full name
 * @param email email address, or {@code null}/blank if not given
 * @param phone phone number, or {@code null}/blank if not given
 * @param notes free-text notes, or {@code null}/blank if not given
 * @param carsCount number of consignment cars of this partner sold and not reverted; maintained
 *     server-side by {@code PartnerService}'s counter hooks (TASK-004), called from {@code
 *     BackOfficeCarService} once the sale/reversal endpoints exist (TASK-010) — never touched by
 *     car creation or deletion
 * @param totalCommission running total commission owed to this partner, incremented/decremented
 *     by the same hooks
 * @param createdAt creation timestamp
 * @param updatedAt last-update timestamp
 */
public record PartnerResponse(
		UUID id,
		String name,
		String email,
		String phone,
		String notes,
		int carsCount,
		BigDecimal totalCommission,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	/**
	 * Builds the response for a given, fully-loaded {@link Partner}.
	 *
	 * @param partner the partner to map, never {@code null}
	 * @return the corresponding response DTO
	 */
	public static PartnerResponse from(Partner partner) {
		return new PartnerResponse(
				partner.getId(),
				partner.getName(),
				partner.getEmail(),
				partner.getPhone(),
				partner.getNotes(),
				partner.getCarsCount(),
				partner.getTotalCommission(),
				partner.getCreatedAt(),
				partner.getUpdatedAt());
	}
}
