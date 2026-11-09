package com.tostao.tostao_api.budget.dto;

import com.tostao.tostao_api.account.MoneyLimits;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Define (cria ou substitui) o limite de um escopo e mês. {@code categoryId} ausente ou nulo é o limite
 * geral. O valor é em centavos inteiros, de 0 a R$ 10 bilhões. Campos fora desta lista, como o workspace,
 * quem criou ou as datas, são recusados.
 */
public record DefineMonthlyBudgetRequest(
		/** Mês no formato AAAA-MM. */
		@NotNull(message = "é obrigatório")
		String month,

		/** UUID da categoria de despesa; ausente ou nulo é o limite geral. Texto vazio ou malformado é recusado. */
		String categoryId,

		@NotNull(message = "é obrigatório")
		@Min(value = 0, message = "deve ser de 0 a 1.000.000.000.000 centavos")
		@Max(value = MoneyLimits.MAX_CENTS, message = "deve ser de 0 a 1.000.000.000.000 centavos")
		Long amountCents) {
}
