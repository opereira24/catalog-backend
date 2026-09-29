package pt.diamondcars.catalogbackend.domain.car;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * {@code @DataJpaTest} for the {@link Car} aggregate (including its {@link CarImage} child
 * entity): confirms it round-trips through the real {@code cars}/{@code car_images} tables with
 * an externally-assigned id (TASK-013 requirement 2/8) and exercises every derived query
 * requirement 8 lists.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CarRepositoryTest extends AbstractPostgresIntegrationTest {

	@Autowired
	private CarRepository carRepository;

	@PersistenceContext
	private EntityManager entityManager;

	private static Car.CarBuilder aCar() {
		return Car.builder()
				.id(UUID.randomUUID())
				.marca("BMW")
				.modelo("320d")
				.ano(2020)
				.preco(new BigDecimal("25000.00"))
				.km(50000)
				.cor("Preto")
				.combustivel("Diesel");
	}

	/**
	 * Confirms a {@link Car} persisted with a fixed, externally-assigned UUID round-trips that
	 * exact id (TASK-013 acceptance criterion: {@code @Id} without {@code @GeneratedValue}) along
	 * with its child {@link CarImage}s (via {@link Car#addImage(CarImage)}), in {@code position}
	 * order.
	 *
	 * <p>Clears the persistence context after flushing and before reloading: {@code images} is
	 * added in reverse position order on purpose, and {@code @OrderBy("position ASC")} only takes
	 * effect when Hibernate actually re-runs the collection query.
	 *
	 * @throws AssertionError if the reloaded car's id does not match the fixed UUID it was saved
	 *     with, or its images are not in the expected order
	 */
	@Test
	void savesAndReloadsACarWithAFixedIdAndItsImagesInPositionOrder() {
		UUID fixedId = UUID.randomUUID();
		Car car = aCar().id(fixedId).build();
		car.addImage(CarImage.builder().url("https://img/2.jpg").position(2).build());
		car.addImage(CarImage.builder().url("https://img/1.jpg").position(1).build());

		carRepository.saveAndFlush(car);
		entityManager.clear();

		Car reloaded = carRepository.findById(fixedId).orElseThrow();

		assertThat(reloaded.getId()).isEqualTo(fixedId);
		assertThat(reloaded.getGarantiaMeses()).isZero();
		assertThat(reloaded.isDestaque()).isFalse();
		assertThat(reloaded.isVendido()).isFalse();
		assertThat(reloaded.isReservado()).isFalse();
		assertThat(reloaded.getImages())
				.extracting(CarImage::getUrl)
				.containsExactly("https://img/1.jpg", "https://img/2.jpg");
	}

	/**
	 * Confirms {@link CarRepository#findByVendidoFalseOrderByCreatedAtDesc(org.springframework.data.domain.Pageable)}
	 * excludes sold cars and returns the rest most recently created first.
	 *
	 * @throws AssertionError if a sold car is included, an unsold one is missing, or the order is
	 *     wrong
	 */
	@Test
	void listsOnlyUnsoldCarsMostRecentlyCreatedFirst() {
		Car firstUnsold = carRepository.saveAndFlush(aCar().modelo("Unsold 1").build());
		Car secondUnsold = carRepository.saveAndFlush(aCar().modelo("Unsold 2").build());
		carRepository.saveAndFlush(aCar().modelo("Sold").vendido(true).build());

		assertThat(carRepository.findByVendidoFalseOrderByCreatedAtDesc(PageRequest.of(0, 10))
				.getContent())
				.extracting(Car::getId)
				.containsExactly(secondUnsold.getId(), firstUnsold.getId());
	}

	/**
	 * Confirms {@link CarRepository#findByDestaqueTrueAndVendidoFalse()} returns only unsold,
	 * highlighted cars.
	 *
	 * @throws AssertionError if a non-highlighted or a sold car is included, or a matching one is
	 *     missing
	 */
	@Test
	void listsOnlyHighlightedUnsoldCars() {
		Car highlighted = carRepository.saveAndFlush(aCar().modelo("Highlighted").destaque(true).build());
		carRepository.saveAndFlush(aCar().modelo("Regular").build());
		carRepository.saveAndFlush(
				aCar().modelo("Highlighted but sold").destaque(true).vendido(true).build());

		assertThat(carRepository.findByDestaqueTrueAndVendidoFalse())
				.extracting(Car::getId)
				.containsExactly(highlighted.getId());
	}
}
