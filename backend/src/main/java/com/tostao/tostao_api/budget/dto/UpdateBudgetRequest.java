package com.tostao.tostao_api.budget.dto;

import com.tostao.tostao_api.account.MoneyLimits;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Novo valor de um limite existente, em centavos inteiros. Só este campo é aceito. */
public record UpdateBudgetRequest(
		@NotNull(message = "é obrigatório")
		@Min(value = 0, message = "deve ser de 0 a 1.000.000.000.000 centavos")
		@Max(value = MoneyLimits.MAX_CENTS, message = "deve ser de 0 a 1.000.000.000.000 centavos")
		Long amountCents) {
}
