package com.tostao.tostao_api.budget.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.tostao.tostao_api.budget.BudgetStatus;

/**
 * Acompanhamento de uma semana: cada limite com o gasto, o restante, o percentual e o estado, mais o total
 * gasto e o gasto que nenhum limite de categoria cobre. Os valores dos limites nunca são somados.
 */
public record WeekBudgetResponse(
		WeekInfo week,
		long totalSpentCents,
		long spentOutsideCategoryLimitsCents,
		List<BudgetItem> budgets) {

	public record WeekInfo(String id, LocalDate startDate, LocalDate endDate) {
	}

	public record BudgetItem(
			UUID id,
			UUID categoryId,
			String categoryName,
			boolean categoryArchived,
			long amountCents,
			long spentCents,
			long remainingCents,
			BigDecimal percentUsed,
			BudgetStatus status,
			UUID createdBy,
			Instant createdAt,
			Instant updatedAt) {
	}

}
