package pt.diamondcars.catalogbackend.domain.support;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.OffsetDateTime;
import lombok.Getter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Extends {@link AbstractGeneratedIdEntity} with creation-timestamp auditing, for the one catalog
 * table that tracks {@code created_at} but not {@code updated_at}: {@code leads} ({@link
 * pt.diamondcars.catalogbackend.domain.lead.Lead}). {@code car_images} has neither column (TASK-013
 * requirement 3 lists only {@code car_id}/{@code url}/{@code thumbnail_url}/{@code position}) and
 * therefore extends {@link AbstractGeneratedIdEntity} directly; {@code cars} tracks both but has a
 * different id strategy, so {@link pt.diamondcars.catalogbackend.domain.car.Car} declares its own
 * {@code createdAt}/{@code updatedAt} instead of extending this class.
 *
 * <p>{@code createdAt} is populated exclusively by Hibernate's {@link CreationTimestamp} — no
 * entity or service sets it by hand.
 */
@Getter
@MappedSuperclass
public abstract class AbstractCreatedAtEntity extends AbstractGeneratedIdEntity {

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;
}
