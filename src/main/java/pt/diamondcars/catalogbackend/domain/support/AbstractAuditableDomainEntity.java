package pt.diamondcars.catalogbackend.domain.support;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.OffsetDateTime;
import lombok.Getter;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Extends {@link AbstractDomainEntity} with last-update auditing, for the tables that track both
 * {@code created_at} and {@code updated_at} ({@code partners}, {@code clients}, {@code cars},
 * {@code transactions}, {@code leads}, {@code app_users}) — as opposed to {@code car_images} and
 * {@code notifications}, which only track creation and therefore extend {@link
 * AbstractDomainEntity} directly. Ported unchanged from {@code dcbo-backend} (TASK-001).
 *
 * <p>{@code updatedAt} is populated exclusively by Hibernate's {@link UpdateTimestamp}, never by
 * application code — hence no setter is exposed.
 */
@Getter
@MappedSuperclass
public abstract class AbstractAuditableDomainEntity extends AbstractDomainEntity {

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;
}
