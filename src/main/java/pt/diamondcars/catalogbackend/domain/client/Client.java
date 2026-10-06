package pt.diamondcars.catalogbackend.domain.client;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import pt.diamondcars.catalogbackend.domain.support.AbstractAuditableDomainEntity;

/**
 * A customer of the back-office, mapped over the {@code clients} table of {@code
 * V2__unified_back_office_schema.sql}. Ported unchanged from {@code dcbo-backend} (TASK-001).
 *
 * <p>{@link #purchasesCount} is a running total maintained on car sale/reversal (equivalent to the
 * increment/decrement in {@code dcbo/src/services/firebaseService.js:315,331}) — this entity only
 * declares the persisted shape.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(of = {"name", "email", "phone", "nif", "purchasesCount"})
@Entity
@Table(name = "clients")
public class Client extends AbstractAuditableDomainEntity {

	@Column(name = "name", nullable = false, length = 255)
	private String name;

	@Column(name = "email", length = 255)
	private String email;

	@Column(name = "phone", nullable = false, length = 50)
	private String phone;

	@Column(name = "nif", length = 20)
	private String nif;

	@Column(name = "address", length = 500)
	private String address;

	@Column(name = "postal_code", length = 20)
	private String postalCode;

	@Column(name = "notes", columnDefinition = "TEXT")
	private String notes;

	@Builder.Default
	@Column(name = "purchases_count", nullable = false)
	private int purchasesCount = 0;
}
