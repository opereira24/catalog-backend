package pt.diamondcars.catalogbackend.domain.lead;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * {@code @DataJpaTest} for the {@link Lead} aggregate: confirms it round-trips through the real
 * {@code leads} table, that its declared default ({@link LeadOrigin#WEBSITE}) survives a
 * save/reload cycle that actually hits the database via its converter, that {@link #carId} stays
 * a plain scalar surviving even when no matching car exists (TASK-013 requirement 5), and
 * exercises {@link LeadRepository#findByForwardedAtIsNull()} (requirement 8).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class LeadRepositoryTest extends AbstractPostgresIntegrationTest {

	@Autowired
	private LeadRepository leadRepository;

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * Confirms a {@link Lead} saved with only its required fields set can be reloaded with its
	 * database-matching default ({@link LeadOrigin#WEBSITE}) intact.
	 *
	 * <p>Uses {@code saveAndFlush} and then clears the persistence context before reloading: a
	 * plain {@code save} followed by {@code findById} inside the same transaction would be served
	 * entirely by Hibernate's first-level cache — no {@code INSERT} or {@code SELECT} would ever
	 * reach the database, and the assertions below would pass regardless of whether the mapping or
	 * the enum converter is even correct.
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
	}

	/**
	 * Confirms a {@link Lead} survives with its {@link Lead#getCarId()} and denormalized snapshot
	 * intact even when no {@code cars} row with that id exists — the whole point of {@code
	 * leads.car_id} having no foreign key (TASK-013 requirement 5).
	 *
	 * @throws AssertionError if the lead fails to save/reload, or loses its car snapshot fields
	 */
	@Test
	void leadSurvivesReferencingACarThatDoesNotExist() {
		UUID danglingCarId = UUID.randomUUID();

		Lead saved = leadRepository.saveAndFlush(Lead.builder()
				.nome("Maria Interessada")
				.telefone("914567890")
				.carId(danglingCarId)
				.carroMarca("Audi")
				.carroModelo("A4")
				.build());
		entityManager.clear();

		Lead reloaded = leadRepository.findById(saved.getId()).orElseThrow();

		assertThat(reloaded.getCarId()).isEqualTo(danglingCarId);
		assertThat(reloaded.getCarroMarca()).isEqualTo("Audi");
		assertThat(reloaded.getCarroModelo()).isEqualTo("A4");
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
