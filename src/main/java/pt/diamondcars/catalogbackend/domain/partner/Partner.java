package pt.diamondcars.catalogbackend.domain.partner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import pt.diamondcars.catalogbackend.domain.support.AbstractAuditableDomainEntity;

/**
 * A consignment/commission partner of the back-office, mapped over the {@code partners} table of
 * {@code V2__unified_back_office_schema.sql}. Partners supply cars sold on consignment ({@code
 * cars.is_consignacao}) and accrue a running commission balance ({@link #totalCommission}) as those
 * cars sell. Ported unchanged from {@code dcbo-backend} (TASK-001).
 *
 * <p>{@link #carsCount} and {@link #totalCommission} are running totals maintained server-side when
 * a consignment car is sold or a sale is reverted — never on car creation or deletion
 * (conceptually equivalent to {@code incrementPartnerCars}/{@code addPartnerCommission} in {@code
 * dcbo/src/services/firebaseService.js}) — this entity only declares the persisted shape, not the
 * increment/decrement logic.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(of = {"name", "email", "phone", "carsCount", "totalCommission"})
@Entity
@Table(name = "partners")
public class Partner extends AbstractAuditableDomainEntity {

	@Column(name = "name", nullable = false, length = 255)
	private String name;

	@Column(name = "email", length = 255)
	private String email;

	@Column(name = "phone", length = 50)
	private String phone;

	@Column(name = "notes", columnDefinition = "TEXT")
	private String notes;

	@Builder.Default
	@Column(name = "cars_count", nullable = false)
	private int carsCount = 0;

	@Builder.Default
	@Column(name = "total_commission", nullable = false, precision = 12, scale = 2)
	private BigDecimal totalCommission = BigDecimal.ZERO;
}
