package com.tostao.tostao_api.budget.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Copia os limites de {@code fromWeek} para as próximas {@code weeks} semanas (1 a 52). */
public record CopyBudgetsRequest(
		@NotNull(message = "é obrigatório")
		String fromWeek,

		@NotNull(message = "é obrigatório")
		@Min(value = 1, message = "deve ser de 1 a 52")
		@Max(value = 52, message = "deve ser de 1 a 52")
		Integer weeks) {
}
