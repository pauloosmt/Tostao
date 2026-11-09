package com.tostao.tostao_api.budget.dto;

import java.time.LocalDate;
import java.util.List;

import com.tostao.tostao_api.budget.dto.WeekBudgetResponse.BudgetItem;

/**
 * Acompanhamento de um mês: cada limite com o gasto, o restante, o percentual e o estado, mais o total gasto
 * e o gasto que nenhum limite de categoria cobre. Os valores dos limites nunca são somados. O item de limite
 * é o mesmo do acompanhamento semanal.
 */
public record MonthBudgetResponse(
		MonthInfo month,
		long totalSpentCents,
		long spentOutsideCategoryLimitsCents,
		List<BudgetItem> budgets) {

	public record MonthInfo(String id, LocalDate startDate, LocalDate endDate) {
	}

}
