package pt.diamondcars.catalogbackend.domain.transaction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.client.Client;
import pt.diamondcars.catalogbackend.domain.partner.Partner;
import pt.diamondcars.catalogbackend.domain.support.AbstractAuditableDomainEntity;

/**
 * A financial movement (car purchase/sale, commission, or generic income/expense) mapped over the
 * {@code transactions} table of {@code V2__unified_back_office_schema.sql}. Ported unchanged from
 * {@code dcbo-backend} (TASK-001).
 *
 * <p>{@code data} is a calendar date, not an instant: it is always sourced from an {@code
 * <input type="date">} in the React frontends, so it is modeled as {@link LocalDate}, never {@link
 * java.time.OffsetDateTime}, to avoid a timezone off-by-one when the value is serialized back in
 * UTC.
 *
 * <p>{@link #systemGenerated} marks a transaction created automatically by a car sale — the
 * counterpart to the {@code venda}/comissão transactions {@code dcbo}'s {@code sellCar} used to
 * write — so reverting the sale can remove exactly the one it created, never a manual transaction
 * that happens to share the same {@code tipo}/{@code car_id}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(of = {"tipo", "valor", "categoria", "data"})
@Entity
@Table(name = "transactions")
public class Transaction extends AbstractAuditableDomainEntity {

	@Column(name = "tipo", nullable = false, length = 50)
	private TransactionType tipo;

	@Column(name = "valor", nullable = false, precision = 12, scale = 2)
	private BigDecimal valor;

	@Column(name = "descricao", length = 500)
	private String descricao;

	@Column(name = "categoria", length = 100)
	private String categoria;

	@Column(name = "data", nullable = false)
	private LocalDate data;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "car_id")
	private Car car;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "client_id")
	private Client client;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "partner_id")
	private Partner partner;

	@Builder.Default
	@Column(name = "system_generated", nullable = false)
	private boolean systemGenerated = false;
}
