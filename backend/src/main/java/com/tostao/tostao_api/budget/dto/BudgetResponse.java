package com.tostao.tostao_api.budget.dto;

import java.time.Instant;
import java.util.UUID;

/** Um limite semanal. {@code categoryId} e {@code categoryName} nulos indicam o limite geral. */
public record BudgetResponse(
		UUID id,
		String week,
		UUID categoryId,
		String categoryName,
		long amountCents,
		UUID createdBy,
		Instant createdAt,
		Instant updatedAt) {
}
