package com.tostao.tostao_api.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.tostao.tostao_api.budget.BudgetTracking.Limit;
import com.tostao.tostao_api.budget.BudgetTracking.Result;
import com.tostao.tostao_api.budget.BudgetTracking.Spent;
import com.tostao.tostao_api.budget.dto.WeekBudgetResponse.BudgetItem;

/**
 * Cálculo do acompanhamento (semana e mês) sem Spring nem banco: é o mesmo código para os dois orçamentos,
 * então estas regras valem para ambos.
 */
class BudgetTrackingUnitTest {

	private static final UUID USER = new UUID(9, 9);
	private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");

	private static final UUID FOOD = new UUID(1, 1);
	private static final UUID MARKET = new UUID(1, 2);
	private static final UUID TRANSPORT = new UUID(1, 3);
	private static final UUID LEISURE = new UUID(1, 4);

	private static Limit limit(long id, UUID category, String name, long amount) {
		return new Limit(new UUID(2, id), category, name, false, amount, USER, NOW, NOW);
	}

	private static Spent spent(UUID category, UUID parent, long cents) {
		return new Spent(category, parent, cents);
	}

	/** 40000 direto em Alimentação, 20000 em Mercado (filha), 36000 em Transporte, 8000 em Lazer, 500 sem categoria. */
	private static List<Spent> spending() {
		return List.of(spent(FOOD, null, 40_000), spent(MARKET, FOOD, 20_000), spent(TRANSPORT, null, 36_000),
				spent(LEISURE, null, 8_000), spent(null, null, 500));
	}

	private static List<Limit> quickstartLimits() {
		return List.of(limit(1, FOOD, "Alimentação", 64_000), limit(2, MARKET, "Mercado", 20_000),
				limit(3, TRANSPORT, "Transporte", 32_000), limit(4, null, null, 400_000));
	}

	private static BudgetItem item(Result result, UUID category) {
		return result.items().stream().filter(i -> java.util.Objects.equals(i.categoryId(), category)).findFirst()
				.orElseThrow();
	}

	@Test
	void withoutLimitsTheItemsAreEmptyAndEverythingIsOutsideTheLimits() {
		Result result = BudgetTracking.track(List.of(), spending());

		assertThat(result.items()).isEmpty();
		assertThat(result.totalSpentCents()).isEqualTo(104_500);
		assertThat(result.spentOutsideCategoryLimitsCents()).isEqualTo(104_500);
	}

	@Test
	void withoutSpendingEveryLimitIsWithinAndEverythingIsZero() {
		Result result = BudgetTracking.track(quickstartLimits(), List.of());

		assertThat(result.totalSpentCents()).isZero();
		assertThat(result.spentOutsideCategoryLimitsCents()).isZero();
		assertThat(result.items()).hasSize(4).allSatisfy(i -> {
			assertThat(i.spentCents()).isZero();
			assertThat(i.status()).isEqualTo(BudgetStatus.WITHIN);
			assertThat(i.remainingCents()).isEqualTo(i.amountCents());
		});
	}

	@Test
	void theGeneralLimitCoversEverythingIncludingUncategorizedSpending() {
		Result result = BudgetTracking.track(List.of(limit(4, null, null, 400_000)), spending());

		BudgetItem general = item(result, null);
		assertThat(general.spentCents()).isEqualTo(104_500);
		assertThat(general.remainingCents()).isEqualTo(295_500);
		assertThat(general.percentUsed()).isEqualByComparingTo(new BigDecimal("26.13"));
		assertThat(general.status()).isEqualTo(BudgetStatus.WITHIN);
	}

	@Test
	void onlyTheGeneralLimitConsumesTheMovementSpendingMinusIncomeNeverBelowZero() {
		List<Limit> limits = List.of(limit(1, FOOD, "Alimentação", 64_000), limit(4, null, null, 400_000));

		Result result = BudgetTracking.track(limits, spending(), 30_000);

		assertThat(item(result, null).spentCents()).isEqualTo(74_500);
		assertThat(item(result, FOOD).spentCents()).isEqualTo(60_000);
		assertThat(result.totalSpentCents()).isEqualTo(104_500);
		assertThat(BudgetTracking.track(limits, spending(), 9_999_999)).satisfies(r -> {
			assertThat(item(r, null).spentCents()).isZero();
			assertThat(item(r, null).status()).isEqualTo(BudgetStatus.WITHIN);
		});
	}

	@Test
	void aPrincipalLimitCoversDirectAndChildSpendingAndASubcategoryOnlyItsOwn() {
		Result result = BudgetTracking.track(quickstartLimits(), spending());

		assertThat(item(result, FOOD).spentCents()).isEqualTo(60_000);
		assertThat(item(result, MARKET).spentCents()).isEqualTo(20_000);
		assertThat(item(result, TRANSPORT).spentCents()).isEqualTo(36_000);
	}

	@Test
	void everyFieldOfTheQuickstartLimitsIsComputed() {
		Result result = BudgetTracking.track(quickstartLimits(), spending());

		BudgetItem transport = item(result, TRANSPORT);
		assertThat(transport.spentCents()).isEqualTo(36_000);
		assertThat(transport.remainingCents()).isEqualTo(-4_000);
		assertThat(transport.percentUsed()).isEqualByComparingTo(new BigDecimal("112.50"));
		assertThat(transport.status()).isEqualTo(BudgetStatus.EXCEEDED);

		BudgetItem food = item(result, FOOD);
		assertThat(food.remainingCents()).isEqualTo(4_000);
		assertThat(food.percentUsed()).isEqualByComparingTo(new BigDecimal("93.75"));
		assertThat(food.status()).isEqualTo(BudgetStatus.WARNING);

		BudgetItem market = item(result, MARKET);
		assertThat(market.remainingCents()).isZero();
		assertThat(market.percentUsed()).isEqualByComparingTo(new BigDecimal("100.00"));
		assertThat(market.status()).isEqualTo(BudgetStatus.WARNING);
	}

	@Test
	void theOutsideTotalIgnoresTheGeneralLimitAndCountsUncategorizedSpending() {
		// com todos os limites de categoria: só Lazer (8000) e o sem categoria (500) ficam de fora
		assertThat(BudgetTracking.track(quickstartLimits(), spending()).spentOutsideCategoryLimitsCents())
				.isEqualTo(8_500);
		// o limite geral sozinho não cobre nada "de categoria": tudo fica de fora
		assertThat(BudgetTracking.track(List.of(limit(4, null, null, 1)), spending()).spentOutsideCategoryLimitsCents())
				.isEqualTo(104_500);
		// um limite de subcategoria tira só o que é dela
		assertThat(BudgetTracking.track(List.of(limit(2, MARKET, "Mercado", 1)), spending())
				.spentOutsideCategoryLimitsCents()).isEqualTo(84_500);
		// um limite de principal tira o direto e as filhas
		assertThat(BudgetTracking.track(List.of(limit(1, FOOD, "Alimentação", 1)), spending())
				.spentOutsideCategoryLimitsCents()).isEqualTo(44_500);
	}

	@Test
	void theTotalNeverDependsOnTheLimitAmounts() {
		long total = BudgetTracking.track(quickstartLimits(), spending()).totalSpentCents();

		assertThat(total).isEqualTo(104_500);
		assertThat(BudgetTracking.track(List.of(limit(1, FOOD, "Alimentação", 999_999_999)), spending())
				.totalSpentCents()).isEqualTo(104_500);
	}

	@Test
	void zeroLimitIsWithinWithoutSpendingAndExceededWithAnyAndHasNoPercent() {
		Result idle = BudgetTracking.track(List.of(limit(1, LEISURE, "Lazer", 0)), List.of());
		BudgetItem none = idle.items().get(0);
		assertThat(none.status()).isEqualTo(BudgetStatus.WITHIN);
		assertThat(none.percentUsed()).isNull();
		assertThat(none.remainingCents()).isZero();

		Result used = BudgetTracking.track(List.of(limit(1, LEISURE, "Lazer", 0)), List.of(spent(LEISURE, null, 1)));
		BudgetItem some = used.items().get(0);
		assertThat(some.status()).isEqualTo(BudgetStatus.EXCEEDED);
		assertThat(some.percentUsed()).isNull();
		assertThat(some.remainingCents()).isEqualTo(-1);
	}

	@Test
	void itemsComeExceededThenWarningThenWithinThenBiggerSpending() {
		Result result = BudgetTracking.track(quickstartLimits(), spending());

		assertThat(result.items()).extracting(BudgetItem::categoryId).containsExactly(TRANSPORT, FOOD, MARKET, null);
		assertThat(result.items()).extracting(BudgetItem::status).containsExactly(BudgetStatus.EXCEEDED,
				BudgetStatus.WARNING, BudgetStatus.WARNING, BudgetStatus.WITHIN);
	}

	@Test
	void tiesAreBrokenByNameWithoutCaseThenGeneralFirstThenId() {
		// sem gasto, todos "dentro do limite" com gasto zero: só o nome e o id desempatam
		List<Limit> limits = List.of(limit(5, new UUID(1, 50), "banana", 1000), limit(3, new UUID(1, 51), "Abacaxi", 1000),
				limit(9, null, null, 1000), limit(2, new UUID(1, 52), "Abacaxi", 1000));

		Result result = BudgetTracking.track(limits, List.of());

		assertThat(result.items()).extracting(BudgetItem::id).containsExactly(new UUID(2, 9), new UUID(2, 2),
				new UUID(2, 3), new UUID(2, 5));
	}

	@Test
	void theResultDoesNotDependOnTheOrderOfTheInputs() {
		Result expected = BudgetTracking.track(quickstartLimits(), spending());

		for (long seed = 1; seed <= 20; seed++) {
			List<Limit> limits = new ArrayList<>(quickstartLimits());
			List<Spent> spending = new ArrayList<>(spending());
			Collections.shuffle(limits, new Random(seed));
			Collections.shuffle(spending, new Random(seed + 100));

			assertThat(BudgetTracking.track(limits, spending)).as("seed " + seed).isEqualTo(expected);
		}
	}

	@Test
	void aSumThatOverflowsALongFailsInsteadOfWrappingAround() {
		List<Spent> huge = List.of(spent(FOOD, null, Long.MAX_VALUE), spent(TRANSPORT, null, 1));

		assertThatThrownBy(() -> BudgetTracking.track(List.of(), huge)).isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> BudgetTracking.track(List.of(limit(4, null, null, 1)), huge))
				.isInstanceOf(ArithmeticException.class);
	}

}
