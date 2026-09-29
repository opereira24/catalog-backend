package pt.diamondcars.catalogbackend.domain.support;

import jakarta.persistence.MappedSuperclass;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.Hibernate;

/**
 * Root mapped superclass for every JPA entity in the catalog domain model.
 *
 * <p>Centralises {@link #equals(Object)}/{@link #hashCode()} as identity-based on {@link
 * #getId()}, deliberately avoiding Lombok's {@code @Data}/{@code @EqualsAndHashCode} (mirrors the
 * decision already made for {@code dcbo-backend}'s equivalent base class), which would pull in
 * mutable/collection fields and break entity semantics inside Hibernate-managed collections and
 * hash-based containers.
 *
 * <p>Unlike {@code dcbo-backend}, this class does not itself declare the {@code @Id} column or
 * its generation strategy: {@link pt.diamondcars.catalogbackend.domain.car.Car} needs an
 * externally-assigned identifier (the same UUID the car already has in {@code dcbo-backend},
 * requirement 2 of TASK-013), while {@link pt.diamondcars.catalogbackend.domain.car.CarImage} and
 * {@link pt.diamondcars.catalogbackend.domain.lead.Lead} need one generated locally. {@link
 * AbstractGeneratedIdEntity} covers the generated case; {@code Car} declares its own assigned
 * {@code @Id} field directly.
 */
@MappedSuperclass
public abstract class AbstractEntity {

	/**
	 * Returns this entity's persistent identifier.
	 *
	 * @return the id, or {@code null} if the entity is not yet persisted
	 */
	public abstract UUID getId();

	/**
	 * Compares entities by identity (persistent {@link #getId()}), the only correct notion of
	 * equality for a JPA entity: two managed instances represent the same row if and only if their
	 * ids match, regardless of which mutable fields happen to be loaded or changed. An entity
	 * without an id yet (transient, not yet persisted) is never equal to any other instance, since
	 * a transient entity has no stable identity.
	 *
	 * @param other the object to compare against
	 * @return {@code true} if {@code other} is a persisted entity of the same runtime type with the
	 *     same id
	 */
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (other == null || Hibernate.getClass(this) != Hibernate.getClass(other)) {
			return false;
		}
		AbstractEntity that = (AbstractEntity) other;
		return getId() != null && Objects.equals(getId(), that.getId());
	}

	/**
	 * Returns a constant hash code for the runtime entity type.
	 *
	 * <p>Deliberately does not hash {@link #getId()} (which is {@code null} until the entity is
	 * first persisted): an entity's hash code must never change over its lifetime, including the
	 * moment it transitions from transient to persistent, or it would violate the hash code
	 * contract while sitting inside a {@link java.util.HashSet}/{@link java.util.HashMap}.
	 *
	 * @return a hash code stable across the entity's entire lifecycle
	 */
	@Override
	public int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}
}
