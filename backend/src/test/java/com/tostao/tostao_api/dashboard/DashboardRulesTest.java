package com.tostao.tostao_api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.tostao.tostao_api.dashboard.DashboardRules.CategoryRow;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse.CategoryGroup;

/** Regras puras do painel (percentual, variação, grupos e saldo acumulado): sem Spring, sem banco. */
class DashboardRulesTest {

	private static final UUID FOOD = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID MARKET = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static final UUID RESTAURANT = UUID.fromString("00000000-0000-0000-0000-00000000000c");
	private static final UUID TRANSPORT = UUID.fromString("00000000-0000-0000-0000-00000000000d");

	private static BigDecimal bd(String value) {
		return new BigDecimal(value);
	}

	@Test
	void percentRoundsHalfUpToTwoDecimals() {
		assertThat(DashboardRules.percent(150, 400)).isEqualTo(bd("37.50"));
		assertThat(DashboardRules.percent(1, 3)).isEqualTo(bd("33.33"));
		assertThat(DashboardRules.percent(2, 3)).isEqualTo(bd("66.67"));
		assertThat(DashboardRules.percent(5, 8)).isEqualTo(bd("62.50"));
		assertThat(DashboardRules.percent(1, 8)).isEqualTo(bd("12.50"));
		assertThat(DashboardRules.percent(0, 100)).isEqualTo(bd("0.00"));
		assertThat(DashboardRules.percent(100, 100)).isEqualTo(bd("100.00"));
	}

	@Test
	void percentDoesNotOverflowWithHugeValues() {
		long big = 4_000_000_000_000_000_000L;

		assertThat(DashboardRules.percent(big, big)).isEqualTo(bd("100.00"));
		assertThat(DashboardRules.percent(big / 4, big)).isEqualTo(bd("25.00"));
	}

	@Test
	void percentWithoutATotalIsRefused() {
		assertThatThrownBy(() -> DashboardRules.percent(1, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void changePercentUsesTheAbsolutePreviousValueAsBase() {
		assertThat(DashboardRules.changePercent(-5000, 20000)).isEqualTo(bd("-25.00"));
		assertThat(DashboardRules.changePercent(5000, -20000)).isEqualTo(bd("25.00"));
		assertThat(DashboardRules.changePercent(255000, -20000)).isEqualTo(bd("1275.00"));
		assertThat(DashboardRules.changePercent(0, 20000)).isEqualTo(bd("0.00"));
		assertThat(DashboardRules.changePercent(-1, 1)).isEqualTo(bd("-100.00"));
	}

	@Test
	void changePercentIsNotApplicableWhenThePreviousValueIsZero() {
		assertThat(DashboardRules.changePercent(100, 0)).isNull();
		assertThat(DashboardRules.changePercent(0, 0)).isNull();
		assertThat(DashboardRules.changePercent(-100, 0)).isNull();
	}

	@Test
	void groupsSumDirectAndChildrenAndComputePercentOverTheTypeTotal() {
		List<CategoryRow> rows = List.of(
				new CategoryRow(FOOD, "Alimentação", FOOD, "Alimentação", 10_000),
				new CategoryRow(FOOD, "Alimentação", MARKET, "Mercado", 5_000),
				new CategoryRow(FOOD, "Alimentação", RESTAURANT, "Restaurante", 3_000),
				new CategoryRow(TRANSPORT, "Transporte", TRANSPORT, "Transporte", 2_000));

		List<CategoryGroup> groups = DashboardRules.groups(rows, 20_000);

		assertThat(groups).hasSize(2);
		CategoryGroup food = groups.get(0);
		assertThat(food.categoryId()).isEqualTo(FOOD);
		assertThat(food.directCents()).isEqualTo(10_000);
		assertThat(food.totalCents()).isEqualTo(18_000);
		assertThat(food.percent()).isEqualTo(bd("90.00"));
		assertThat(food.children()).extracting(c -> c.name()).containsExactly("Mercado", "Restaurante");
		assertThat(food.children().get(0).percent()).isEqualTo(bd("25.00"));
		assertThat(food.children().get(1).percent()).isEqualTo(bd("15.00"));
		assertThat(groups.get(1).children()).isEmpty();
		assertThat(groups.stream().mapToLong(CategoryGroup::totalCents).sum()).isEqualTo(20_000);
	}

	@Test
	void aGroupWithOnlySubcategoriesHasNoDirectAmount() {
		List<CategoryRow> rows = List.of(new CategoryRow(FOOD, "Alimentação", MARKET, "Mercado", 7_000));

		CategoryGroup food = DashboardRules.groups(rows, 7_000).get(0);

		assertThat(food.directCents()).isZero();
		assertThat(food.totalCents()).isEqualTo(7_000);
		assertThat(food.children()).hasSize(1);
	}

	@Test
	void groupsAndChildrenAreSortedByTotalThenNameThenId() {
		UUID a = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID b = UUID.fromString("00000000-0000-0000-0000-000000000002");
		UUID c = UUID.fromString("00000000-0000-0000-0000-000000000003");
		List<CategoryRow> rows = List.of(
				new CategoryRow(a, "lazer", a, "lazer", 1_000),
				new CategoryRow(b, "Casa", b, "Casa", 1_000),
				new CategoryRow(c, "Zeta", c, "Zeta", 5_000));

		List<CategoryGroup> groups = DashboardRules.groups(rows, 7_000);

		assertThat(groups).extracting(g -> g.name()).containsExactly("Zeta", "Casa", "lazer");
	}

	@Test
	void equalNamesFallBackToTheIdentifier() {
		UUID low = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID high = UUID.fromString("00000000-0000-0000-0000-000000000002");
		List<CategoryRow> rows = List.of(
				new CategoryRow(high, "Outros", high, "Outros", 100),
				new CategoryRow(low, "Outros", low, "Outros", 100));

		List<CategoryGroup> groups = DashboardRules.groups(rows, 200);

		assertThat(groups).extracting(CategoryGroup::categoryId).containsExactly(low, high);
	}

	@Test
	void uncategorizedAmountsFormTheNoCategoryGroup() {
		List<CategoryRow> rows = List.of(
				new CategoryRow(null, null, null, null, 2_500),
				new CategoryRow(FOOD, "Alimentação", FOOD, "Alimentação", 7_500));

		List<CategoryGroup> groups = DashboardRules.groups(rows, 10_000);

		CategoryGroup none = groups.get(1);
		assertThat(none.categoryId()).isNull();
		assertThat(none.name()).isEqualTo(DashboardRules.NO_CATEGORY);
		assertThat(none.totalCents()).isEqualTo(2_500);
		assertThat(none.directCents()).isEqualTo(2_500);
		assertThat(none.children()).isEmpty();
		assertThat(none.percent()).isEqualTo(bd("25.00"));
	}

	@Test
	void noRowsMeansNoGroups() {
		assertThat(DashboardRules.groups(List.of(), 0)).isEmpty();
	}

	@Test
	void runningBalancesAccumulateTheMovementFromTheOpening() {
		LocalDate d1 = LocalDate.of(2026, 11, 2);
		List<LocalDate> days = List.of(d1, d1.plusDays(1), d1.plusDays(2));

		List<Long> balances = DashboardRules.runningBalances(80_000, days,
				Map.of(d1, -10_000L, d1.plusDays(2), 250_000L));

		assertThat(balances).containsExactly(70_000L, 70_000L, 320_000L);
		assertThat(balances.get(balances.size() - 1)).isEqualTo(80_000 - 10_000 + 250_000);
	}

	@Test
	void runningBalancesWithoutMovementRepeatTheOpening() {
		List<LocalDate> days = List.of(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 3));

		assertThat(DashboardRules.runningBalances(5, days, Map.of())).containsExactly(5L, 5L);
		assertThat(DashboardRules.runningBalances(5, List.of(), Map.of())).isEmpty();
	}

	@Test
	void sumsFailLoudlyOnOverflow() {
		assertThat(DashboardRules.sumExact(List.of(1L, 2L, 3L))).isEqualTo(6L);
		assertThat(DashboardRules.sumExact(List.of())).isZero();
		assertThatThrownBy(() -> DashboardRules.sumExact(List.of(Long.MAX_VALUE, 1L)))
				.isInstanceOf(ArithmeticException.class);
	}

}
