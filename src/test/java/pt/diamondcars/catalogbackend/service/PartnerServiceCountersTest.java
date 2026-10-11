package pt.diamondcars.catalogbackend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import pt.diamondcars.catalogbackend.domain.partner.Partner;

/**
 * Unit tests for {@link PartnerService}'s counter hooks (TASK-004 AC D) — pure object mutation,
 * no repository or Spring context involved, since no caller exists yet in this task (TASK-010
 * wires {@code BackOfficeCarService#sell}/{@code revertSale} to these on sale/reversal). Proves
 * each hook correct in isolation ahead of that integration.
 */
class PartnerServiceCountersTest {

	private final PartnerService partnerService = new PartnerService(null, null);

	@Test
	void incrementCarsCountAddsOne() {
		Partner partner = Partner.builder().carsCount(2).build();

		partnerService.incrementCarsCount(partner);

		assertThat(partner.getCarsCount()).isEqualTo(3);
	}

	@Test
	void decrementCarsCountSubtractsOneButNeverBelowZero() {
		Partner partner = Partner.builder().carsCount(1).build();

		partnerService.decrementCarsCount(partner);
		assertThat(partner.getCarsCount()).isZero();

		partnerService.decrementCarsCount(partner);
		assertThat(partner.getCarsCount()).isZero();
	}

	@Test
	void registerCommissionAddsToTheRunningTotal() {
		Partner partner = Partner.builder().totalCommission(new BigDecimal("100.00")).build();

		partnerService.registerCommission(partner, new BigDecimal("50.00"));

		assertThat(partner.getTotalCommission()).isEqualByComparingTo(new BigDecimal("150.00"));
	}

	@Test
	void registerCommissionIsANoOpWhenCommissionIsNull() {
		Partner partner = Partner.builder().totalCommission(new BigDecimal("100.00")).build();

		partnerService.registerCommission(partner, null);

		assertThat(partner.getTotalCommission()).isEqualByComparingTo(new BigDecimal("100.00"));
	}

	@Test
	void reverseCommissionSubtractsFromTheRunningTotalButNeverBelowZero() {
		Partner partner = Partner.builder().totalCommission(new BigDecimal("30.00")).build();

		partnerService.reverseCommission(partner, new BigDecimal("50.00"));

		assertThat(partner.getTotalCommission()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void reverseCommissionIsANoOpWhenCommissionIsNull() {
		Partner partner = Partner.builder().totalCommission(new BigDecimal("100.00")).build();

		partnerService.reverseCommission(partner, null);

		assertThat(partner.getTotalCommission()).isEqualByComparingTo(new BigDecimal("100.00"));
	}
}
