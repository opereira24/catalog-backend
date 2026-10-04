package pt.diamondcars.catalogbackend.domain.support;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import pt.diamondcars.catalogbackend.domain.lead.Lead;
import pt.diamondcars.catalogbackend.domain.lead.LeadOrigin;
import pt.diamondcars.catalogbackend.domain.lead.LeadRepository;
import pt.diamondcars.catalogbackend.domain.lead.LeadStatus;
import pt.diamondcars.catalogbackend.domain.transaction.Transaction;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionRepository;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionType;
import pt.diamondcars.catalogbackend.domain.user.AppUser;
import pt.diamondcars.catalogbackend.domain.user.AppUserRepository;
import pt.diamondcars.catalogbackend.domain.user.AppUserRole;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * {@code @DataJpaTest} that fixes, as an executable contract, the exact database representation
 * every {@link PersistentEnum} constant is persisted as through {@link
 * AbstractPersistentEnumConverter}. This is the whole justification {@code dcbo-backend}'s design
 * gave for not using plain {@code @Enumerated(EnumType.STRING)}: the schema requires
 * lower snake_case (the {@code CHECK} constraint on {@code leads.status}), and {@code
 * leads.origem = 'website-contacto'} even requires a hyphen, which {@link Enum#name()} could
 * never produce.
 *
 * <p>Unlike the "save and reload" tests in each aggregate's own repository test — which read the
 * value back through {@link AbstractPersistentEnumConverter#convertToEntityAttribute(String)} and
 * so cannot tell a correct converter from a broken one that happens to be internally consistent
 * (e.g. one that silently switched to persisting {@link Enum#name()}) — every assertion here reads
 * the column's raw text with a native query, bypassing the converter entirely on the read side.
 * Covers every constant of all 4 {@link PersistentEnum} implementations in the domain model
 * ({@link LeadStatus}, {@link LeadOrigin}, {@link TransactionType}, {@link AppUserRole}), not just
 * a sample, so an incomplete converter mapping (one that silently mis-maps a rarely-used
 * constant) cannot hide behind an untested value.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EnumPersistenceRepresentationTest extends AbstractPostgresIntegrationTest {

	@Autowired
	private LeadRepository leadRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private AppUserRepository appUserRepository;

	@PersistenceContext
	private EntityManager entityManager;

	/**
	 * Confirms every {@link LeadStatus} constant is persisted in {@code leads.status} as its exact
	 * {@link LeadStatus#getValue()} string — lower snake_case, as the column's {@code CHECK}
	 * constraint requires, never {@link Enum#name()}.
	 *
	 * @param status the constant under test, supplied once per {@link LeadStatus} value
	 * @throws AssertionError if the raw column value does not equal {@link LeadStatus#getValue()}
	 */
	@ParameterizedTest
	@EnumSource(LeadStatus.class)
	void persistsEachLeadStatusAsItsExactSchemaString(LeadStatus status) {
		Lead lead = leadRepository.saveAndFlush(
				Lead.builder().nome("Enum Probe").telefone("910000000").status(status).build());

		assertThat(rawColumnValue("leads", "status", lead.getId())).isEqualTo(status.getValue());
	}

	/**
	 * Confirms every {@link LeadOrigin} constant is persisted in {@code leads.origem} as its exact
	 * {@link LeadOrigin#getValue()} string — including {@link LeadOrigin#WEBSITE_CONTACTO}, whose
	 * hyphenated value ({@code "website-contacto"}) is the actual reason this converter-based
	 * approach exists instead of {@code @Enumerated(EnumType.STRING)}: no Java enum constant name
	 * can contain a hyphen.
	 *
	 * @param origin the constant under test, supplied once per {@link LeadOrigin} value
	 * @throws AssertionError if the raw column value does not equal {@link LeadOrigin#getValue()}
	 */
	@ParameterizedTest
	@EnumSource(LeadOrigin.class)
	void persistsEachLeadOriginAsItsExactSchemaString(LeadOrigin origin) {
		Lead lead = leadRepository.saveAndFlush(
				Lead.builder().nome("Enum Probe").telefone("910000000").origem(origin).build());

		assertThat(rawColumnValue("leads", "origem", lead.getId())).isEqualTo(origin.getValue());
	}

	/**
	 * Confirms every {@link TransactionType} constant is persisted in {@code transactions.tipo} as
	 * its exact {@link TransactionType#getValue()} string.
	 *
	 * @param type the constant under test, supplied once per {@link TransactionType} value
	 * @throws AssertionError if the raw column value does not equal {@link TransactionType#getValue()}
	 */
	@ParameterizedTest
	@EnumSource(TransactionType.class)
	void persistsEachTransactionTypeAsItsExactSchemaString(TransactionType type) {
		Transaction transaction = transactionRepository.saveAndFlush(Transaction.builder()
				.tipo(type)
				.valor(new BigDecimal("1.00"))
				.data(LocalDate.of(2026, 1, 1))
				.build());

		assertThat(rawColumnValue("transactions", "tipo", transaction.getId()))
				.isEqualTo(type.getValue());
	}

	/**
	 * Confirms every {@link AppUserRole} constant is persisted in {@code app_users.role} as its
	 * exact {@link AppUserRole#getValue()} string.
	 *
	 * @param role the constant under test, supplied once per {@link AppUserRole} value
	 * @throws AssertionError if the raw column value does not equal {@link AppUserRole#getValue()}
	 */
	@ParameterizedTest
	@EnumSource(AppUserRole.class)
	void persistsEachAppUserRoleAsItsExactSchemaString(AppUserRole role) {
		AppUser user = appUserRepository.saveAndFlush(AppUser.builder()
				.authSubject("auth0|" + UUID.randomUUID())
				.email("probe@diamondcars.pt")
				.name("Enum Probe")
				.role(role)
				.build());

		assertThat(rawColumnValue("app_users", "role", user.getId())).isEqualTo(role.getValue());
	}

	/**
	 * Reads the raw text stored in a column for a given row via a native query, bypassing any JPA
	 * {@link jakarta.persistence.AttributeConverter} on the read side — the only way to distinguish
	 * a converter that writes the intended schema string from one that writes something else but
	 * happens to read itself back correctly.
	 *
	 * @param table the table to query
	 * @param column the column to read
	 * @param id the row's primary key
	 * @return the column's value cast to text, exactly as stored in the database
	 */
	private String rawColumnValue(String table, String column, UUID id) {
		return (String) entityManager
				.createNativeQuery("select cast(" + column + " as text) from " + table + " where id = :id")
				.setParameter("id", id)
				.getSingleResult();
	}
}
