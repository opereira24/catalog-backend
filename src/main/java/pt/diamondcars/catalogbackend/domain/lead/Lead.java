package pt.diamondcars.catalogbackend.domain.lead;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import pt.diamondcars.catalogbackend.domain.support.AbstractCreatedAtEntity;

/**
 * A sales lead submitted directly by the public site ({@code dc}), mapped over the {@code leads}
 * table of {@code V1__init.sql}.
 *
 * <p>{@link #carId} is a plain scalar UUID, deliberately <b>not</b> a {@code @ManyToOne}
 * association: {@code leads.car_id} has no {@code REFERENCES cars} constraint (TASK-013
 * requirement 5, {@code backlog/CONVENTIONS.md} ADR-001), precisely so a lead survives a car that
 * later disappears from this catalog's projection. Modeling it as an association would make a
 * lazy fetch of a dangling reference throw, defeating that guarantee. {@link #carroMarca}/{@link
 * #carroModelo} are a denormalized snapshot of the car at submission time, kept even if the car
 * itself is gone — both stay in Portuguese by design (ADR-001, Camada 2), mirroring what the
 * {@code dc} frontend already sends.
 *
 * <p>{@link #forwardedAt}/{@link #forwardAttempts} track this lead's own forwarding to {@code
 * dcbo-backend} (the actual owner of the lead, with its {@code status}/{@code notas} workflow) —
 * out of scope for this task (TASK-013), consumed by the forwarding task that follows it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(of = {"nome", "telefone", "origem"})
@Entity
@Table(name = "leads")
public class Lead extends AbstractCreatedAtEntity {

	@Column(name = "nome", nullable = false, length = 255)
	private String nome;

	@Column(name = "email", length = 255)
	private String email;

	@Column(name = "telefone", nullable = false, length = 50)
	private String telefone;

	@Column(name = "mensagem", columnDefinition = "TEXT")
	private String mensagem;

	@Column(name = "car_id")
	private UUID carId;

	@Column(name = "carro_marca", length = 100)
	private String carroMarca;

	@Column(name = "carro_modelo", length = 100)
	private String carroModelo;

	@Builder.Default
	@Column(name = "origem", nullable = false, length = 100)
	private LeadOrigin origem = LeadOrigin.WEBSITE;

	@Column(name = "forwarded_at")
	private OffsetDateTime forwardedAt;

	@Builder.Default
	@Column(name = "forward_attempts", nullable = false)
	private int forwardAttempts = 0;
}
