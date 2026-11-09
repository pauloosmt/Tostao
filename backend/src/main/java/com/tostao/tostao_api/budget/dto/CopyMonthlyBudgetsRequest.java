package com.tostao.tostao_api.budget.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Copia os limites de {@code fromMonth} (AAAA-MM) para os próximos {@code months} meses (1 a 24). */
public record CopyMonthlyBudgetsRequest(
		@NotNull(message = "é obrigatório")
		String fromMonth,

		@NotNull(message = "é obrigatório")
		@Min(value = 1, message = "deve ser de 1 a 24")
		@Max(value = 24, message = "deve ser de 1 a 24")
		Integer months) {
}
