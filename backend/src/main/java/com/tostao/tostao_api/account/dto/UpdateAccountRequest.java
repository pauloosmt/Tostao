package com.tostao.tostao_api.account.dto;

import com.tostao.tostao_api.account.AccountType;
import com.tostao.tostao_api.account.MoneyLimits;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Edição de conta ativa. O tipo é opcional e só existe para o cliente poder devolver o objeto inteiro:
 * se vier diferente do atual, a edição é recusada. As demais regras são as do cadastro. Campos fora
 * desta lista (como {@code archived} e {@code balanceCents}) são recusados.
 */
public record UpdateAccountRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 100, message = "deve ter no máximo 100 caracteres")
		String name,

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

	public UpdateAccountRequest {
		name = name == null ? null : name.strip();
	}

}
