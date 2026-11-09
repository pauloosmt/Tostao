package com.tostao.tostao_api.budget.dto;

import java.time.Instant;
import java.util.UUID;

/** Um limite mensal. {@code categoryId} e {@code categoryName} nulos indicam o limite geral. */
public record MonthlyBudgetResponse(
		UUID id,
		String month,
		UUID categoryId,
		String categoryName,
		long amountCents,
		UUID createdBy,
		Instant createdAt,
		Instant updatedAt) {
}
