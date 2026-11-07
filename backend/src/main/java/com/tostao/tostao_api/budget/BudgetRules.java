package com.tostao.tostao_api.budget;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

/**
 * Regras puras do orçamento semanal (sem Spring nem banco): percentual, estado, cobertura de categoria e
 * ordenação. Valores em centavos inteiros; o estado é decidido com inteiros exatos, nunca com o percentual
 * arredondado. A mesma regra serve de base ao orçamento mensal da fase 2.
 */
public final class BudgetRules {

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private BudgetRules() {
	}

	/**
	 * {@code gasto × 100 / limite}, com duas casas e metade para cima. Limite zero devolve {@code null}
	 * ("não aplicável").
	 */
	public static BigDecimal percent(long spentCents, long limitCents) {
		requireNotNegative(spentCents, limitCents);
		if (limitCents == 0) {
			return null;
		}
		return BigDecimal.valueOf(spentCents).multiply(HUNDRED)
				.divide(BigDecimal.valueOf(limitCents), 2, RoundingMode.HALF_UP);
	}

	/**
	 * Abaixo de 80% do limite é {@code WITHIN}; de 80% a 100% (inclusive) é {@code WARNING}; acima de 100%
	 * é {@code EXCEEDED}. Limite zero: sem gasto é {@code WITHIN}, com qualquer gasto é {@code EXCEEDED}.
	 */
	public static BudgetStatus status(long spentCents, long limitCents) {
		requireNotNegative(spentCents, limitCents);
		if (limitCents == 0) {
			return spentCents == 0 ? BudgetStatus.WITHIN : BudgetStatus.EXCEEDED;
		}
		// gasto / limite < 80%  <=>  gasto * 5 < limite * 4
		if (Math.multiplyExact(spentCents, 5L) < Math.multiplyExact(limitCents, 4L)) {
			return BudgetStatus.WITHIN;
		}
		return spentCents <= limitCents ? BudgetStatus.WARNING : BudgetStatus.EXCEEDED;
	}

	/** Limite menos gasto; negativo quando estourado. */
	public static long remaining(long spentCents, long limitCents) {
		return Math.subtractExact(limitCents, spentCents);
	}

	/**
	 * O limite de {@code limitCategoryId} cobre o gasto lançado em {@code spentCategoryId} (cujo pai é
	 * {@code spentCategoryParentId})? Limite geral ({@code limitCategoryId} nulo) cobre tudo, inclusive
	 * despesa sem categoria. Limite de categoria cobre a própria categoria e as filhas dela.
	 */
	public static boolean covers(UUID limitCategoryId, UUID spentCategoryId, UUID spentCategoryParentId) {
		if (limitCategoryId == null) {
			return true;
		}
		return limitCategoryId.equals(spentCategoryId) || limitCategoryId.equals(spentCategoryParentId);
	}

	/** Item a ordenar: o limite geral tem {@code categoryName} nulo. */
	public record Item(UUID id, String categoryName, long spentCents, BudgetStatus status) {
	}

	/**
	 * Estado (estourado, atenção, dentro do limite), maior gasto, nome da categoria sem diferenciar
	 * maiúsculas (o geral antes dos nomeados) e identificador: a mesma resposta em consultas repetidas.
	 */
	public static Comparator<Item> order() {
		return Comparator
				.comparing((Item i) -> i.status().ordinal(), Comparator.reverseOrder())
				.thenComparing(Item::spentCents, Comparator.reverseOrder())
				.thenComparing(i -> i.categoryName() == null ? null : i.categoryName().toLowerCase(Locale.ROOT),
						Comparator.nullsFirst(Comparator.naturalOrder()))
				.thenComparing(Item::id);
	}

	/** Soma exata: estouro de {@code long} lança {@link ArithmeticException}. */
	public static long sumExact(Collection<Long> values) {
		long sum = 0;
		for (long value : values) {
			sum = Math.addExact(sum, value);
		}
		return sum;
	}

	private static void requireNotNegative(long spentCents, long limitCents) {
		if (spentCents < 0 || limitCents < 0) {
			throw new IllegalArgumentException("gasto e limite não podem ser negativos");
		}
	}

}
