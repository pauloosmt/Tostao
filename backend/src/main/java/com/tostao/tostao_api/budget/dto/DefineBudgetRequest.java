package com.tostao.tostao_api.budget.dto;

import java.util.UUID;

import com.tostao.tostao_api.account.MoneyLimits;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Define (cria ou substitui) o limite de um escopo e semana. {@code categoryId} ausente ou nulo é o limite
 * geral. O valor é em centavos inteiros, de 0 a R$ 10 bilhões. Campos fora desta lista, como o workspace,
 * quem criou ou as datas, são recusados.
 */
public record DefineBudgetRequest(
		@NotNull(message = "é obrigatório")
		String week,

		UUID categoryId,

		@NotNull(message = "é obrigatório")
		@Min(value = 0, message = "deve ser de 0 a 1.000.000.000.000 centavos")
		@Max(value = MoneyLimits.MAX_CENTS, message = "deve ser de 0 a 1.000.000.000.000 centavos")
		Long amountCents) {
}
