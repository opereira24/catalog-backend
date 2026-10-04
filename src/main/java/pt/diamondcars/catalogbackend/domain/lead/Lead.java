package pt.diamondcars.catalogbackend.domain.lead;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.support.AbstractAuditableDomainEntity;

/**
 * A sales lead mapped over the {@code leads} table ({@code V1__init.sql} plus the back-office
 * columns of {@code V2__unified_back_office_schema.sql}), submitted by the public site ({@code
 * dc}) or created in the back-office ({@code dcbo}). One table for both: the lead the site sends
 * is the record the back-office works (TASK-001).
 *
 * <p>{@link #car} maps {@code car_id}, a foreign key to {@code cars} with {@code ON DELETE SET
 * NULL} since V2: deleting a car keeps its leads, without car. {@link #carroMarca}/{@link
 * #carroModelo}/{@link #carroPreco} are a denormalized snapshot of the car at submission time, so
 * the lead still says which car it was about after the car is gone. They stay in Portuguese by
 * design (ADR-001, layer 2: business attributes, not FKs).
 *
 * <p>The {@link #status} builder default ({@link LeadStatus#CONTACTADO}) comes from {@code
 * dcbo-backend} and differs from the database default ({@code 'ativo'}); every production writer
 * sets the status explicitly ({@code LeadService#createFromWebsite} writes {@link
 * LeadStatus#ATIVO}).
 *
 * <p>{@link #forwardedAt}/{@link #forwardAttempts} track the forwarding of this lead to {@code
 * dcbo-backend}, which only exists until that backend is retired.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(of = {"nome", "telefone", "status", "origem"})
@Entity
@Table(name = "leads")
public class Lead extends AbstractAuditableDomainEntity {

	@Column(name = "nome", nullable = false, length = 255)
	private String nome;

	@Column(name = "email", length = 255)
	private String email;

	@Column(name = "telefone", nullable = false, length = 50)
	private String telefone;

	@Column(name = "mensagem", columnDefinition = "TEXT")
	private String mensagem;

	@Column(name = "notas", columnDefinition = "TEXT")
	private String notas;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "car_id")
	private Car car;

	@Column(name = "carro_marca", length = 100)
	private String carroMarca;

	@Column(name = "carro_modelo", length = 100)
	private String carroModelo;

	@Column(name = "carro_preco", precision = 12, scale = 2)
	private BigDecimal carroPreco;

	@Column(name = "follow_up_date")
	private LocalDate followUpDate;

	@Builder.Default
	@Column(name = "status", nullable = false, length = 50)
	private LeadStatus status = LeadStatus.CONTACTADO;

	@Builder.Default
	@Column(name = "origem", nullable = false, length = 100)
	private LeadOrigin origem = LeadOrigin.WEBSITE;

	@Column(name = "forwarded_at")
	private OffsetDateTime forwardedAt;

	@Builder.Default
	@Column(name = "forward_attempts", nullable = false)
	private int forwardAttempts = 0;
}
