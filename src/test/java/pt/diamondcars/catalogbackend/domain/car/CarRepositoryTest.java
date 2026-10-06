package pt.diamondcars.catalogbackend.domain.car;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import pt.diamondcars.catalogbackend.domain.client.Client;
import pt.diamondcars.catalogbackend.domain.partner.Partner;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * {@code @DataJpaTest} for the {@link Car} aggregate (including its {@link CarImage} child
 * entity): confirms it round-trips through the real {@code cars}/{@code car_images} tables of the
 * unified schema (V1 + V2) with a generated id and every back-office field, and exercises every
 * derived query of {@link CarRepository}.
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
				.marca("BMW")
				.modelo("320d")
				.ano(2020)
				.preco(new BigDecimal("25000.00"))
				.km(50000)
				.cor("Preto")
				.combustivel("Diesel")
				.transmissao("Manual")
				.origem("Nacional");
	}

	/**
	 * Confirms a {@link Car} saved without an id gets one generated, round-trips it with its
	 * declared defaults, and reloads its child {@link CarImage}s (added via {@link
	 * Car#addImage(CarImage)}) in {@code position} order, each with a {@code createdAt}.
	 *
	 * <p>Clears the persistence context after flushing and before reloading: {@code images} is
	 * added in reverse position order on purpose, and {@code @OrderBy("position ASC")} only takes
	 * effect when Hibernate actually re-runs the collection query.
	 *
	 * @throws AssertionError if the id is not generated, the reloaded car does not match, or its
	 *     images are not in the expected order
	 */
	@Test
	void savesAndReloadsACarWithAGeneratedIdAndItsImagesInPositionOrder() {
		Car car = aCar().build();
		car.addImage(CarImage.builder().url("https://img/2.jpg").position(2).build());
		car.addImage(CarImage.builder().url("https://img/1.jpg").position(1).build());

		Car saved = carRepository.saveAndFlush(car);
		UUID generatedId = saved.getId();
		entityManager.clear();

		Car reloaded = carRepository.findById(generatedId).orElseThrow();

		assertThat(generatedId).isNotNull();
		assertThat(reloaded.getId()).isEqualTo(generatedId);
		assertThat(reloaded.getGarantiaMeses()).isZero();
		assertThat(reloaded.isDestaque()).isFalse();
		assertThat(reloaded.isVendido()).isFalse();
		assertThat(reloaded.isReservado()).isFalse();
		assertThat(reloaded.isConsignacao()).isFalse();
		assertThat(reloaded.getImages())
				.extracting(CarImage::getUrl)
				.containsExactly("https://img/1.jpg", "https://img/2.jpg");
		assertThat(reloaded.getImages()).allSatisfy(image -> assertThat(image.getCreatedAt()).isNotNull());
	}

	/**
	 * Confirms every back-office field V2 added to {@code cars} — partner and client associations
	 * included — plus the two fields only the catalog had ({@code observacoes}, {@code syncedAt})
	 * survive a real {@code INSERT} and {@code SELECT}: the persistence context is cleared before
	 * reloading, so nothing is served from Hibernate's first-level cache.
	 *
	 * @throws AssertionError if any field does not round-trip
	 */
	@Test
	void persistsEveryInternalFieldIncludingPartnerAndClient() {
		Partner partner = Partner.builder().name("Auto Silva").build();
		Client client = Client.builder().name("Ana Compradora").phone("912345678").build();
		entityManager.persist(partner);
		entityManager.persist(client);
		OffsetDateTime dataVenda = OffsetDateTime.of(2026, 9, 1, 15, 30, 0, 0, ZoneOffset.UTC);
		OffsetDateTime syncedAt = OffsetDateTime.of(2026, 8, 1, 9, 0, 0, 0, ZoneOffset.UTC);

		Car saved = carRepository.saveAndFlush(aCar()
				.transmissao("Automática")
				.origem("Importado")
				.observacoes("Revisão feita")
				.precoCompra(new BigDecimal("18000.00"))
				.consignacao(true)
				.partner(partner)
				.commissionValue(new BigDecimal("750.50"))
				.dataCompra(LocalDate.of(2026, 7, 15))
				.vendido(true)
				.dataVenda(dataVenda)
				.precoVenda(new BigDecimal("24500.00"))
				.client(client)
				.syncedAt(syncedAt)
				.build());
		entityManager.clear();

		Car reloaded = carRepository.findById(saved.getId()).orElseThrow();

		assertThat(reloaded.getTransmissao()).isEqualTo("Automática");
		assertThat(reloaded.getOrigem()).isEqualTo("Importado");
		assertThat(reloaded.getObservacoes()).isEqualTo("Revisão feita");
		assertThat(reloaded.getPrecoCompra()).isEqualByComparingTo("18000.00");
		assertThat(reloaded.isConsignacao()).isTrue();
		assertThat(reloaded.getPartner().getId()).isEqualTo(partner.getId());
		assertThat(reloaded.getPartner().getName()).isEqualTo("Auto Silva");
		assertThat(reloaded.getCommissionValue()).isEqualByComparingTo("750.50");
		assertThat(reloaded.getDataCompra()).isEqualTo(LocalDate.of(2026, 7, 15));
		assertThat(reloaded.isVendido()).isTrue();
		assertThat(reloaded.getDataVenda()).isAtSameInstantAs(dataVenda);
		assertThat(reloaded.getPrecoVenda()).isEqualByComparingTo("24500.00");
		assertThat(reloaded.getClient().getId()).isEqualTo(client.getId());
		assertThat(reloaded.getClient().getName()).isEqualTo("Ana Compradora");
		assertThat(reloaded.getSyncedAt()).isAtSameInstantAs(syncedAt);
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

	/**
	 * Confirms {@link CarRepository#countByDestaqueTrue()} counts every featured car, sold or not,
	 * and only those.
	 *
	 * @throws AssertionError if the count is not exactly the number of featured cars
	 */
	@Test
	void countsEveryFeaturedCar() {
		long featuredBefore = carRepository.countByDestaqueTrue();

		carRepository.saveAndFlush(aCar().destaque(true).build());
		carRepository.saveAndFlush(aCar().destaque(true).vendido(true).build());
		carRepository.saveAndFlush(aCar().build());

		assertThat(carRepository.countByDestaqueTrue()).isEqualTo(featuredBefore + 2);
	}

	/**
	 * Confirms the client lookups ({@link CarRepository#findByClientIdOrderByCreatedAtDesc(UUID)},
	 * {@link CarRepository#existsByClientId(UUID)}) see only the cars of that client, most recent
	 * first.
	 *
	 * @throws AssertionError if another client's car is included, the order is wrong, or {@code
	 *     existsByClientId} answers wrongly
	 */
	@Test
	void findsTheCarsOfAClientMostRecentlyCreatedFirst() {
		Client client = Client.builder().name("Cliente").phone("911111111").build();
		Client otherClient = Client.builder().name("Outro").phone("922222222").build();
		Client clientWithoutCars = Client.builder().name("Sem Carros").phone("933333333").build();
		entityManager.persist(client);
		entityManager.persist(otherClient);
		entityManager.persist(clientWithoutCars);
		Car first = carRepository.saveAndFlush(aCar().client(client).build());
		Car second = carRepository.saveAndFlush(aCar().client(client).build());
		carRepository.saveAndFlush(aCar().client(otherClient).build());

		assertThat(carRepository.findByClientIdOrderByCreatedAtDesc(client.getId()))
				.extracting(Car::getId)
				.containsExactly(second.getId(), first.getId());
		assertThat(carRepository.existsByClientId(client.getId())).isTrue();
		assertThat(carRepository.existsByClientId(clientWithoutCars.getId())).isFalse();
	}

	/**
	 * Confirms the partner lookups ({@link
	 * CarRepository#findByPartnerIdOrderByCreatedAtDesc(UUID)}, {@link
	 * CarRepository#existsByPartnerId(UUID)}) see only the cars of that partner, most recent first.
	 *
	 * @throws AssertionError if another partner's car is included, the order is wrong, or {@code
	 *     existsByPartnerId} answers wrongly
	 */
	@Test
	void findsTheCarsOfAPartnerMostRecentlyCreatedFirst() {
		Partner partner = Partner.builder().name("Parceiro").build();
		Partner otherPartner = Partner.builder().name("Outro Parceiro").build();
		Partner partnerWithoutCars = Partner.builder().name("Sem Carros").build();
		entityManager.persist(partner);
		entityManager.persist(otherPartner);
		entityManager.persist(partnerWithoutCars);
		Car first = carRepository.saveAndFlush(aCar().consignacao(true).partner(partner).build());
		Car second = carRepository.saveAndFlush(aCar().consignacao(true).partner(partner).build());
		carRepository.saveAndFlush(aCar().consignacao(true).partner(otherPartner).build());

		assertThat(carRepository.findByPartnerIdOrderByCreatedAtDesc(partner.getId()))
				.extracting(Car::getId)
				.containsExactly(second.getId(), first.getId());
		assertThat(carRepository.existsByPartnerId(partner.getId())).isTrue();
		assertThat(carRepository.existsByPartnerId(partnerWithoutCars.getId())).isFalse();
	}
}
