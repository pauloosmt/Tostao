package com.tostao.tostao_api.budget;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.tostao.tostao_api.budget.BudgetRules.Item;
import com.tostao.tostao_api.budget.dto.WeekBudgetResponse.BudgetItem;

/**
 * Cálculo do acompanhamento de um período (semana ou mês), sem Spring nem banco. Recebe os limites e as
 * despesas por categoria já lidos e devolve, para cada limite, gasto, restante, percentual e estado, mais o
 * total gasto e o gasto que nenhum limite de categoria cobre. É a mesma regra para o orçamento semanal e
 * para o mensal: cada serviço lê do banco com o seu SQL e chama {@link #track}. Os valores dos limites
 * nunca são somados entre si.
 */
final class BudgetTracking {

	/** Limite do período com a categoria (nome nulo e {@code archived} falso no limite geral). */
	record Limit(UUID id, UUID categoryId, String categoryName, boolean archived, long amountCents,
			UUID createdBy, Instant createdAt, Instant updatedAt) {
	}

	/** Despesa do período agrupada por categoria (e categoria pai), em centavos positivos. */
	record Spent(UUID categoryId, UUID parentId, long cents) {
	}

	record Result(long totalSpentCents, long spentOutsideCategoryLimitsCents, List<BudgetItem> items) {
	}

	private BudgetTracking() {
	}

	static Result track(List<Limit> limits, List<Spent> spent) {
		return track(limits, spent, 0);
	}

	/**
	 * Como {@link #track(List, List)}, mas o limite geral consome a movimentação do período: as despesas que ele
	 * cobre menos as entradas efetivadas ({@code incomeCents}), nunca abaixo de zero. Limites de categoria
	 * continuam só com as despesas da categoria (categoria de despesa não recebe entrada). O total gasto e o
	 * gasto fora dos limites seguem sendo só despesas.
	 */
	static Result track(List<Limit> limits, List<Spent> spent, long incomeCents) {
		long total = BudgetRules.sumExact(spent.stream().map(Spent::cents).toList());
		List<UUID> categoryLimits = limits.stream().map(Limit::categoryId).filter(Objects::nonNull).toList();
		long outside = BudgetRules.sumExact(spent.stream()
				.filter(s -> categoryLimits.stream().noneMatch(l -> BudgetRules.covers(l, s.categoryId(), s.parentId())))
				.map(Spent::cents).toList());

		List<BudgetItem> items = new ArrayList<>();
		for (Limit limit : limits) {
			long spentCents = BudgetRules.sumExact(spent.stream()
					.filter(s -> BudgetRules.covers(limit.categoryId(), s.categoryId(), s.parentId()))
					.map(Spent::cents).toList());
			if (limit.categoryId() == null) {
				spentCents = Math.max(0, Math.subtractExact(spentCents, incomeCents));
			}
			items.add(new BudgetItem(limit.id(), limit.categoryId(), limit.categoryName(), limit.archived(),
					limit.amountCents(), spentCents, BudgetRules.remaining(spentCents, limit.amountCents()),
					BudgetRules.percent(spentCents, limit.amountCents()),
					BudgetRules.status(spentCents, limit.amountCents()), limit.createdBy(), limit.createdAt(),
					limit.updatedAt()));
		}
		items.sort(Comparator.comparing(i -> new Item(i.id(), i.categoryName(), i.spentCents(), i.status()),
				BudgetRules.order()));
		return new Result(total, outside, items);
	}

}
