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
import pt.diamondcars.catalogbackend.domain.support.AbstractDomainEntity;

/**
 * A single photo of a {@link Car}, mapped over the {@code car_images} child table ({@code
 * created_at} added by {@code V2__unified_back_office_schema.sql}; photos older than V2 carry their
 * car's {@code created_at}, the honest lower bound).
 *
 * <p>Owned by {@link Car} ({@code car_id ON DELETE CASCADE}): always created/removed through
 * {@link Car#addImage(CarImage)}/{@link Car#removeImage(CarImage)}, never persisted independently —
 * hence no dedicated {@code CarImageRepository}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(of = {"url", "position"})
@Entity
@Table(name = "car_images")
public class CarImage extends AbstractDomainEntity {

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
