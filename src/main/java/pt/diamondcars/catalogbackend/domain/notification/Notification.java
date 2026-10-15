package pt.diamondcars.catalogbackend.domain.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.support.AbstractDomainEntity;

/**
 * A back-office notification (e.g. an overdue follow-up alert) mapped over the {@code
 * notifications} table of {@code V2__unified_back_office_schema.sql}. Ported unchanged from {@code
 * dcbo-backend} (TASK-001).
 *
 * <p>{@code tipo}/{@code prioridade} are plain strings, not {@link
 * pt.diamondcars.catalogbackend.domain.support.PersistentEnum}s: unlike {@code role}, transaction
 * {@code tipo} and lead {@code status}/{@code origem}, the real values written for notifications
 * (e.g. {@code dcbo/src/App.js:169} {@code follow_up}/{@code follow_up_atrasado}, {@code
 * dcbo/src/pages/settings.js:69} {@code aviso}) are not exhaustively enumerable from the frontend
 * code, and the column has no {@code CHECK} constraint to anchor a closed set against.
 *
 * <p>{@code lead_id} cascades {@code ON DELETE CASCADE} at the database level (a notification has
 * no meaning once its lead is gone), so no JPA-level cascade is declared here — the database
 * already guarantees it.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(of = {"tipo", "titulo", "prioridade", "read"})
@Entity
@Table(name = "notifications")
public class Notification extends AbstractDomainEntity {

	@Column(name = "tipo", nullable = false, length = 100)
	private String tipo;

	@Column(name = "titulo", length = 255)
	private String titulo;

	@Column(name = "mensagem", nullable = false, columnDefinition = "TEXT")
	private String mensagem;

	@Column(name = "prioridade", length = 20)
	private String prioridade;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "lead_id")
	private Lead lead;

	@Builder.Default
	@Column(name = "read", nullable = false)
	private boolean read = false;
}
