package pt.diamondcars.catalogbackend.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import pt.diamondcars.catalogbackend.domain.transaction.Transaction;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionRepository;
import pt.diamondcars.catalogbackend.domain.transaction.TransactionType;
import pt.diamondcars.catalogbackend.support.AbstractPostgresIntegrationTest;

/**
 * End-to-end tests of {@link FinanceController} and {@code TransactionService#summary} through the
 * real filter chain (TASK-005, AC D.2), with {@link MockMvc} against the shared PostgreSQL container
 * and the same context configuration as {@code ClientControllerTest}.
 *
 * <p>The first 4 tests are ported from {@code dcbo-backend}'s {@code FinanceControllerTest} with
 * the same method names; the rest are new.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class FinanceControllerTest extends AbstractPostgresIntegrationTest {

	private static final String PATH = "/api/backoffice/finances/summary";

	@Autowired private MockMvc mockMvc;
	@Autowired private TransactionRepository transactionRepository;

	/** The summary reads every transaction, so the table starts empty for each test. */
	@BeforeEach
	void cleanDatabase() {
		transactionRepository.deleteAllInBatch();
	}

	private void save(TransactionType tipo, String valor, LocalDate data, boolean systemGenerated) {
		transactionRepository.saveAndFlush(Transaction.builder()
				.tipo(tipo)
				.valor(new BigDecimal(valor))
				.data(data)
				.systemGenerated(systemGenerated)
				.build());
	}

	private void save(TransactionType tipo, String valor, LocalDate data) {
		save(tipo, valor, data, false);
	}

	// ---------------------------------------------------------------------------------------------
	// Ported from dcbo-backend (AC D.2, same method names)
	// ---------------------------------------------------------------------------------------------

	/**
	 * A 1000.00 receita and a 250.50 despesa inside the interval give {@code saldo} 749.50; a receita
	 * outside it does not count.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void computesTheExactSaldoForKnownReceitasAndDespesas() throws Exception {
		save(TransactionType.RECEITA, "1000.00", LocalDate.of(2026, 2, 10));
		save(TransactionType.DESPESA, "250.50", LocalDate.of(2026, 2, 15));
		save(TransactionType.RECEITA, "5000.00", LocalDate.of(2026, 3, 1));

		mockMvc
				.perform(get(PATH + "?from=2026-02-01&to=2026-02-28").with(user()))
				.andExpect(status().isOk())
				.andExpect(amount("totalReceitas", "1000.00"))
				.andExpect(amount("totalDespesas", "250.50"))
				.andExpect(amount("saldo", "749.50"));
	}

	/**
	 * {@code venda} counts with {@code receita} and {@code compra} with {@code despesa} ({@code
	 * calculateBalance} of {@code dcbo/src/App.js:196-200}).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void groupsVendaWithReceitaAndCompraWithDespesa() throws Exception {
		save(TransactionType.VENDA, "15000.00", LocalDate.of(2026, 4, 1));
		save(TransactionType.COMPRA, "10000.00", LocalDate.of(2026, 4, 2));

		mockMvc
				.perform(get(PATH + "?from=2026-04-01&to=2026-04-30").with(user()))
				.andExpect(status().isOk())
				.andExpect(amount("totalReceitas", "15000.00"))
				.andExpect(amount("totalDespesas", "10000.00"))
				.andExpect(amount("saldo", "5000.00"));
	}

	/**
	 * Without {@code from}/{@code to} the summary covers every transaction.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void summaryWithoutABoundedRangeCoversEveryTransaction() throws Exception {
		save(TransactionType.RECEITA, "100.00", LocalDate.of(2020, 1, 1));
		save(TransactionType.RECEITA, "200.00", LocalDate.of(2030, 1, 1));

		mockMvc
				.perform(get(PATH).with(user()))
				.andExpect(status().isOk())
				.andExpect(amount("totalReceitas", "300.00"));
	}

	/**
	 * The summary responds 401 without a token.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void summaryWithoutATokenIsUnauthorized() throws Exception {
		mockMvc
				.perform(get(PATH))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value("Autenticacao necessaria"));
	}

	// ---------------------------------------------------------------------------------------------
	// New in TASK-005 (AC D.2)
	// ---------------------------------------------------------------------------------------------

	/**
	 * AC D.2, fixed set: the totals of 5 requests over 6 known transactions (expected values summed
	 * with Python's {@code decimal}, not by hand). Each value is asserted on the response text, which
	 * fixes the number and its 2 decimal places without depending on key order or on JsonPath's
	 * number conversion. Covers: inclusive bounds, a system-generated venda counted, the whole
	 * history, a negative {@code saldo}, a period with nothing, and {@code from} after {@code to}.
	 *
	 * @param query the query string of the request
	 * @param totalReceitas expected {@code totalReceitas} text
	 * @param totalDespesas expected {@code totalDespesas} text
	 * @param saldo expected {@code saldo} text
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@ParameterizedTest(name = "{0}")
	@CsvSource({
		"?from=2026-03-01&to=2026-03-31, 1500.50, 700.25, 800.25",
		"'',                             2500.49, 750.25, 1750.24",
		"?from=2026-04-01&to=2026-04-30, 0.00,    50.00,  -50.00",
		"?from=2030-01-01,               0.00,    0.00,   0.00",
		"?from=2026-03-31&to=2026-03-01, 0.00,    0.00,   0.00"
	})
	void totalsMatchAFixedSetOfKnownTransactions(String query, String totalReceitas, String totalDespesas, String saldo)
			throws Exception {
		save(TransactionType.RECEITA, "1000.10", LocalDate.of(2026, 3, 1));
		save(TransactionType.VENDA, "500.40", LocalDate.of(2026, 3, 31), true);
		save(TransactionType.DESPESA, "300.25", LocalDate.of(2026, 3, 15));
		save(TransactionType.COMPRA, "400.00", LocalDate.of(2026, 3, 10));
		save(TransactionType.RECEITA, "999.99", LocalDate.of(2026, 2, 28));
		save(TransactionType.DESPESA, "50.00", LocalDate.of(2026, 4, 1));

		mockMvc
				.perform(get(PATH + query).with(user()))
				.andExpect(status().isOk())
				.andExpect(amount("totalReceitas", totalReceitas))
				.andExpect(amount("totalDespesas", totalDespesas))
				.andExpect(amount("saldo", saldo));
	}

	/**
	 * AC D.2: a token without a role is 403 with the fixed message.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void aTokenWithoutARoleIsForbidden() throws Exception {
		mockMvc
				.perform(get(PATH).with(jwt().authorities(
						new SimpleGrantedAuthority("FACTOR_BEARER"), new SimpleGrantedAuthority("SCOPE_openid"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value("Autenticado, mas sem a role exigida para esta operacao"));
	}

	/**
	 * AC D.2: an invalid date is a 400 naming the parameter.
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void anInvalidFromIsABadRequest() throws Exception {
		mockMvc
				.perform(get(PATH + "?from=2026-13-01").with(user()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Parametro 'from' com valor invalido"));
	}

	/**
	 * Bounds outside PostgreSQL's {@code DATE} range (parsed by {@code ?from=}/{@code ?to=}) answer
	 * 200 with the right totals, never the 409 that PostgreSQL's {@code date out of range} becomes (ASSUNÇÃO of the developer, TASK-005
	 * Notas).
	 *
	 * @throws Exception propagated from {@link MockMvc#perform}
	 */
	@Test
	void boundsBeyondTheDatabaseRangeAnswerWithoutError() throws Exception {
		save(TransactionType.RECEITA, "10.00", LocalDate.of(2026, 1, 1));

		mockMvc
				.perform(get(PATH).param("from", "-5000-01-01").param("to", "+999999999-12-31").with(user()))
				.andExpect(status().isOk())
				.andExpect(amount("totalReceitas", "10.00"));
		mockMvc
				.perform(get(PATH).param("to", "-5000-01-01").with(user()))
				.andExpect(status().isOk())
				.andExpect(amount("totalReceitas", "0.00"));
		mockMvc
				.perform(get(PATH).param("from", "+999999999-12-31").with(user()))
				.andExpect(status().isOk())
				.andExpect(amount("totalReceitas", "0.00"));
	}

	/**
	 * Matches a response whose JSON has {@code "key":value} followed by a comma or a closing brace: fixes
	 * the number and its exact decimal places ({@code 1500.50} does not match {@code 1500.500}),
	 * whatever the key order.
	 */
	private static ResultMatcher amount(String key, String value) {
		String pair = "\"" + key + "\":" + value;
		return content().string(anyOf(containsString(pair + ","), containsString(pair + "}")));
	}

	private static RequestPostProcessor user() {
		return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
