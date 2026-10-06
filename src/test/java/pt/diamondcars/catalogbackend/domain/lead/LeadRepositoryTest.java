package pt.diamondcars.catalogbackend.domain.lead;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import pt.diamondcars.catalogbackend.domain.car.Car;
import pt.diamondcars.catalogbackend.domain.car.CarRepository;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * {@code @DataJpaTest} for the {@link Lead} aggregate: confirms it round-trips through the real
 * {@code leads} table, that its declared defaults survive a save/reload cycle that actually hits
 * the database via their converters, that a lead outlives the car it is about ({@code car_id ON
 * DELETE SET NULL}, V2), and exercises {@link LeadRepository#findByForwardedAtIsNull()}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LeadRepositoryTest extends AbstractPostgresIntegrationTest {

	@Autowired
	private LeadRepository leadRepository;

	@Autowired
	private CarRepository carRepository;

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * Confirms a {@link Lead} saved with only its required fields set can be reloaded with its
	 * builder defaults ({@link LeadOrigin#WEBSITE}, {@link LeadStatus#CONTACTADO}) intact.
	 *
	 * <p>Uses {@code saveAndFlush} and then clears the persistence context before reloading: a
	 * plain {@code save} followed by {@code findById} inside the same transaction would be served
	 * entirely by Hibernate's first-level cache — no {@code INSERT} or {@code SELECT} would ever
	 * reach the database, and the assertions below would pass regardless of whether the mapping or
	 * the enum converters are even correct.
	 *
	 * @throws AssertionError if the reloaded lead does not match what was saved
	 */
	@Test
	void savesAndReloadsALeadWithDefaults() {
		Lead saved = leadRepository.saveAndFlush(
				Lead.builder().nome("Joao Cliente").telefone("913456789").build());
		entityManager.clear();

		Lead reloaded = leadRepository.findById(saved.getId()).orElseThrow();

		assertThat(reloaded).isNotSameAs(saved);
		assertThat(reloaded.getOrigem()).isEqualTo(LeadOrigin.WEBSITE);
		assertThat(reloaded.getStatus()).isEqualTo(LeadStatus.CONTACTADO);
		assertThat(reloaded.getCreatedAt()).isNotNull();
		assertThat(reloaded.getUpdatedAt()).isNotNull();
	}

	/**
	 * Confirms a lead outlives the car it is about: once the car is deleted, the lead is still
	 * there, without car ({@code car_id} set to {@code NULL} by the {@code ON DELETE SET NULL} foreign
	 * key of V2), with its denormalized snapshot ({@code carroMarca}/{@code carroModelo}/{@code
	 * carroPreco}) intact — the snapshot is what still says which car the lead was about.
	 *
	 * @throws AssertionError if the lead is deleted with the car, keeps a dangling car, or loses its
	 *     snapshot fields
	 */
	@Test
	void leadSurvivesTheDeletionOfItsCarWithoutCarAndWithItsSnapshot() {
		Car car = carRepository.saveAndFlush(Car.builder()
				.marca("Audi")
				.modelo("A4")
				.ano(2020)
				.preco(new BigDecimal("28000.00"))
				.km(40000)
				.cor("Branco")
				.combustivel("Gasolina")
				.transmissao("Manual")
				.origem("Nacional")
				.build());
		Lead saved = leadRepository.saveAndFlush(Lead.builder()
				.nome("Maria Interessada")
				.telefone("914567890")
				.car(car)
				.carroMarca("Audi")
				.carroModelo("A4")
				.carroPreco(new BigDecimal("28000.00"))
				.build());
		entityManager.clear();
		assertThat(leadRepository.findById(saved.getId()).orElseThrow().getCar().getId())
				.isEqualTo(car.getId());
		entityManager.clear();

		carRepository.deleteById(car.getId());
		carRepository.flush();
		entityManager.clear();

		Lead reloaded = leadRepository.findById(saved.getId()).orElseThrow();
		assertThat(carRepository.existsById(car.getId())).isFalse();
		assertThat(reloaded.getCar()).isNull();
		assertThat(reloaded.getCarroMarca()).isEqualTo("Audi");
		assertThat(reloaded.getCarroModelo()).isEqualTo("A4");
		assertThat(reloaded.getCarroPreco()).isEqualByComparingTo("28000.00");
	}

	/**
	 * Confirms {@link LeadRepository#findByForwardedAtIsNull()} returns only leads not yet
	 * forwarded to {@code dcbo-backend}.
	 *
	 * @throws AssertionError if an already-forwarded lead is included, or a pending one is missing
	 */
	@Test
	void findsOnlyLeadsNotYetForwarded() {
		Lead pending = leadRepository.saveAndFlush(
				Lead.builder().nome("Pendente").telefone("911111111").build());
		leadRepository.saveAndFlush(Lead.builder()
				.nome("Ja Encaminhado")
				.telefone("922222222")
				.forwardedAt(OffsetDateTime.now())
				.build());

		assertThat(leadRepository.findByForwardedAtIsNull())
				.extracting(Lead::getId)
				.containsExactly(pending.getId());
	}
}
