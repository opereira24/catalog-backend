package pt.diamondcars.catalogbackend.domain.car;

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
import pt.diamondcars.catalogbackend.domain.support.AbstractGeneratedIdEntity;

/**
 * A single photo of a {@link Car}, mapped over the {@code car_images} child table of {@code
 * V1__init.sql}.
 *
 * <p>Owned by {@link Car} ({@code car_id ON DELETE CASCADE}, TASK-013 requirement 3): always
 * created/removed through {@link Car#addImage(CarImage)}/{@link Car#removeImage(CarImage)}, never
 * persisted independently — hence no dedicated {@code CarImageRepository}. Unlike {@code
 * dcbo-backend}'s equivalent table, {@code car_images} here has neither {@code created_at} nor
 * {@code updated_at} (TASK-013 requirement 3 lists only {@code car_id}/{@code url}/{@code
 * thumbnail_url}/{@code position}), so this entity extends {@link AbstractGeneratedIdEntity}
 * directly instead of an auditable variant.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(of = {"url", "position"})
@Entity
@Table(name = "car_images")
public class CarImage extends AbstractGeneratedIdEntity {

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "car_id", nullable = false)
	private Car car;

	@Column(name = "url", nullable = false, length = 1000)
	private String url;

	@Column(name = "thumbnail_url", length = 1000)
	private String thumbnailUrl;

	@Builder.Default
	@Column(name = "position", nullable = false)
	private int position = 0;
}
