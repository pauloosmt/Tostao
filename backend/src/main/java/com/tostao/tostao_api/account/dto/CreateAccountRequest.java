package com.tostao.tostao_api.account.dto;

import com.tostao.tostao_api.account.AccountType;
import com.tostao.tostao_api.account.MoneyLimits;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cadastro de conta. Dinheiro sempre em centavos inteiros. O nome chega sem espaços nas pontas.
 * Limite e dias só valem para cartão de crédito (regra conferida no serviço). Campos fora desta
 * lista, como o workspace, o saldo calculado ou o estado de arquivada, são recusados.
 */
public record CreateAccountRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 100, message = "deve ter no máximo 100 caracteres")
		String name,

		@NotNull(message = "é obrigatório")
		AccountType type,

		@NotNull(message = "é obrigatório")
		@Min(value = -MoneyLimits.MAX_CENTS, message = "deve ser de -1.000.000.000.000 a 1.000.000.000.000 centavos")
		@Max(value = MoneyLimits.MAX_CENTS, message = "deve ser de -1.000.000.000.000 a 1.000.000.000.000 centavos")
		Long initialBalanceCents,

		@Min(value = 0, message = "deve ser zero ou positivo")
		@Max(value = MoneyLimits.MAX_CENTS, message = "deve ser no máximo 1.000.000.000.000 centavos")
		Long creditLimitCents,

		@Min(value = 1, message = "deve ser de 1 a 31")
		@Max(value = 31, message = "deve ser de 1 a 31")
		Integer closingDay,

		@Min(value = 1, message = "deve ser de 1 a 31")
		@Max(value = 31, message = "deve ser de 1 a 31")
		Integer dueDay) {

	public CreateAccountRequest {
		name = name == null ? null : name.strip();
	}

}
