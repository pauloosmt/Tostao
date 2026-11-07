package com.tostao.tostao_api.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.tostao.tostao_api.budget.BudgetRules.Item;

/** Regras puras do orçamento semanal: sem Spring, sem banco (princípio VIII). */
class BudgetRulesTest {

	private static final UUID ROOT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID CHILD = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
	private static final UUID SIBLING = UUID.fromString("00000000-0000-0000-0000-0000000000a3");

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}

	@Test
	void percentHasTwoDecimalsAndRoundsHalfUp() {
		assertThat(BudgetRules.percent(15000, 40000)).isEqualTo(bd("37.50"));
		assertThat(BudgetRules.percent(15000, 40000).scale()).isEqualTo(2);
		assertThat(BudgetRules.percent(1, 3)).isEqualTo(bd("33.33"));
		assertThat(BudgetRules.percent(2, 3)).isEqualTo(bd("66.67"));
		assertThat(BudgetRules.percent(5, 8)).isEqualTo(bd("62.50"));
		assertThat(BudgetRules.percent(1, 8)).isEqualTo(bd("12.50"));
		assertThat(BudgetRules.percent(1, 16)).isEqualTo(bd("6.25"));
		assertThat(BudgetRules.percent(0, 100)).isEqualTo(bd("0.00"));
		assertThat(BudgetRules.percent(9000, 8000)).isEqualTo(bd("112.50"));
		assertThat(BudgetRules.percent(10001, 10000)).isEqualTo(bd("100.01"));
		assertThat(BudgetRules.percent(7999, 10000)).isEqualTo(bd("79.99"));
	}

	@Test
	void percentOfAZeroLimitIsNotApplicable() {
		assertThat(BudgetRules.percent(150, 0)).isNull();
		assertThat(BudgetRules.percent(0, 0)).isNull();
	}

	@Test
	void percentDoesNotOverflowOnTheLargestValues() {
		long max = 1_000_000_000_000L;

		assertThat(BudgetRules.percent(max, max)).isEqualTo(bd("100.00"));
		assertThat(BudgetRules.percent(Long.MAX_VALUE / 100, max)).isNotNull();
	}

	@Test
	void statusBoundariesAreDecidedWithExactCents() {
		assertThat(BudgetRules.status(7999, 10000)).isEqualTo(BudgetStatus.WITHIN);
		assertThat(BudgetRules.status(8000, 10000)).isEqualTo(BudgetStatus.WARNING);
		assertThat(BudgetRules.status(10000, 10000)).isEqualTo(BudgetStatus.WARNING);
		assertThat(BudgetRules.status(10001, 10000)).isEqualTo(BudgetStatus.EXCEEDED);
		assertThat(BudgetRules.status(0, 10000)).isEqualTo(BudgetStatus.WITHIN);
		assertThat(BudgetRules.status(2, 3)).isEqualTo(BudgetStatus.WITHIN);
		assertThat(BudgetRules.status(6, 7)).isEqualTo(BudgetStatus.WARNING);
	}

	@Test
	void theRoundedPercentageNeverDecidesTheStatus() {
		// 79.999% arredonda para 80.00, mas ainda está abaixo de 80%
		assertThat(BudgetRules.percent(79999, 100000)).isEqualTo(bd("80.00"));
		assertThat(BudgetRules.status(79999, 100000)).isEqualTo(BudgetStatus.WITHIN);
		assertThat(BudgetRules.status(80000, 100000)).isEqualTo(BudgetStatus.WARNING);
		// 100.004% arredonda para 100.00, mas já passou do limite
		assertThat(BudgetRules.percent(100004, 100000)).isEqualTo(bd("100.00"));
		assertThat(BudgetRules.status(100004, 100000)).isEqualTo(BudgetStatus.EXCEEDED);
	}

	@Test
	void aZeroLimitIsWithinOnlyWithoutSpending() {
		assertThat(BudgetRules.status(0, 0)).isEqualTo(BudgetStatus.WITHIN);
		assertThat(BudgetRules.status(1, 0)).isEqualTo(BudgetStatus.EXCEEDED);
	}

	@Test
	void statusHandlesTheLargestValuesWithoutOverflow() {
		long max = 1_000_000_000_000L;

		assertThat(BudgetRules.status(max, max)).isEqualTo(BudgetStatus.WARNING);
		assertThat(BudgetRules.status(max - 1, max)).isEqualTo(BudgetStatus.WARNING);
		assertThat(BudgetRules.status(max / 2, max)).isEqualTo(BudgetStatus.WITHIN);
		assertThat(BudgetRules.status(max + 1, max)).isEqualTo(BudgetStatus.EXCEEDED);
	}

	@Test
	void negativeAmountsAreRejected() {
		assertThatThrownBy(() -> BudgetRules.status(-1, 100)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> BudgetRules.status(1, -100)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> BudgetRules.percent(-1, 100)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void remainingIsNegativeWhenExceeded() {
		assertThat(BudgetRules.remaining(15000, 40000)).isEqualTo(25000);
		assertThat(BudgetRules.remaining(9000, 8000)).isEqualTo(-1000);
		assertThat(BudgetRules.remaining(0, 0)).isZero();
	}

	@Test
	void aGeneralLimitCoversEveryExpenseIncludingUncategorizedOnes() {
		assertThat(BudgetRules.covers(null, null, null)).isTrue();
		assertThat(BudgetRules.covers(null, ROOT, null)).isTrue();
		assertThat(BudgetRules.covers(null, CHILD, ROOT)).isTrue();
	}

	@Test
	void aRootLimitCoversItselfAndItsChildrenOnly() {
		assertThat(BudgetRules.covers(ROOT, ROOT, null)).isTrue();
		assertThat(BudgetRules.covers(ROOT, CHILD, ROOT)).isTrue();
		assertThat(BudgetRules.covers(ROOT, SIBLING, null)).isFalse();
		assertThat(BudgetRules.covers(ROOT, null, null)).isFalse();
	}

	@Test
	void aSubcategoryLimitCoversOnlyItsOwnSpending() {
		assertThat(BudgetRules.covers(CHILD, CHILD, ROOT)).isTrue();
		assertThat(BudgetRules.covers(CHILD, ROOT, null)).isFalse();
		assertThat(BudgetRules.covers(CHILD, SIBLING, ROOT)).isFalse();
	}

	private static Item item(int id, String name, long spent, BudgetStatus status) {
		return new Item(new UUID(0, id), name, spent, status);
	}

	@Test
	void theOrderIsStatusThenSpendingThenNameThenId() {
		Item exceededSmall = item(1, "Beta", 100, BudgetStatus.EXCEEDED);
		Item exceededBig = item(2, "Alfa", 900, BudgetStatus.EXCEEDED);
		Item warning = item(3, "Zeta", 5000, BudgetStatus.WARNING);
		Item within = item(4, "Alfa", 9000, BudgetStatus.WITHIN);

		List<Item> sorted = new ArrayList<>(List.of(within, warning, exceededSmall, exceededBig));
		sorted.sort(BudgetRules.order());

		assertThat(sorted).containsExactly(exceededBig, exceededSmall, warning, within);
	}

	@Test
	void aTieOnSpendingIsBrokenByNameIgnoringCaseWithTheGeneralFirst() {
		Item general = item(9, null, 500, BudgetStatus.WITHIN);
		Item lower = item(1, "alimentação", 500, BudgetStatus.WITHIN);
		Item upper = item(2, "Transporte", 500, BudgetStatus.WITHIN);
		Item sameNameLowId = item(3, "Zeta", 500, BudgetStatus.WITHIN);
		Item sameNameHighId = item(4, "ZETA", 500, BudgetStatus.WITHIN);

		List<Item> sorted = new ArrayList<>(List.of(sameNameHighId, upper, lower, sameNameLowId, general));
		sorted.sort(BudgetRules.order());

		assertThat(sorted).containsExactly(general, lower, upper, sameNameLowId, sameNameHighId);
	}

	@Test
	void theOrderIsTheSameForAnyPermutationOfTheInput() {
		List<Item> items = List.of(item(1, "Beta", 100, BudgetStatus.EXCEEDED), item(2, "Alfa", 100, BudgetStatus.EXCEEDED),
				item(3, null, 100, BudgetStatus.EXCEEDED), item(4, "Gama", 7, BudgetStatus.WARNING),
				item(5, "gama", 7, BudgetStatus.WARNING), item(6, "Delta", 1, BudgetStatus.WITHIN));
		List<Item> expected = new ArrayList<>(items);
		expected.sort(BudgetRules.order());

		Random random = new Random(20261107L);
		for (int i = 0; i < 50; i++) {
			List<Item> shuffled = new ArrayList<>(items);
			Collections.shuffle(shuffled, random);
			shuffled.sort(BudgetRules.order());
			assertThat(shuffled).isEqualTo(expected);
		}
	}

	@Test
	void sumExactFailsOnOverflow() {
		assertThat(BudgetRules.sumExact(List.of(10L, 20L, 30L))).isEqualTo(60);
		assertThat(BudgetRules.sumExact(List.of())).isZero();
		assertThatThrownBy(() -> BudgetRules.sumExact(List.of(Long.MAX_VALUE, 1L)))
				.isInstanceOf(ArithmeticException.class);
	}

}
