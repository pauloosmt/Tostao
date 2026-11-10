package com.tostao.tostao_api.transaction.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.tostao.tostao_api.account.MoneyLimits;
import com.tostao.tostao_api.transaction.InstallmentRules;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Registro de uma compra parcelada. O valor chega positivo, em centavos inteiros, e é dividido em
 * {@code installmentCount} parcelas mensais a partir de {@code firstOccurredOn}. A descrição é a base: cada parcela
 * recebe o sufixo " (N/T)". Campos fora desta lista, como o workspace, quem criou, o grupo ou o número de cada
 * parcela, são recusados. As regras que cruzam campos (total mínimo por parcela, faixa de datas) e as de conta e
 * categoria são conferidas no serviço, antes de qualquer gravação.
 */
public record CreateInstallmentPurchaseRequest(
		@NotNull(message = "é obrigatório")
		UUID accountId,

		@NotNull(message = "é obrigatório")
		UUID categoryId,

		@NotNull(message = "é obrigatório")
		@Min(value = 1, message = "deve ser de 1 a 1.000.000.000.000 centavos")
		@Max(value = MoneyLimits.MAX_CENTS, message = "deve ser de 1 a 1.000.000.000.000 centavos")
		Long totalCents,

		@NotNull(message = "é obrigatório")
		@Min(value = InstallmentRules.MIN_COUNT, message = "deve ser de 2 a 48")
		@Max(value = InstallmentRules.MAX_COUNT, message = "deve ser de 2 a 48")
		Integer installmentCount,

		@NotNull(message = "é obrigatório")
		LocalDate firstOccurredOn,

		@NotBlank(message = "é obrigatório")
		@Size(max = InstallmentRules.MAX_DESCRIPTION, message = "deve ter no máximo 190 caracteres")
		String description,

		@Size(max = 1000, message = "deve ter no máximo 1.000 caracteres")
		String notes,

		/** Estado da primeira parcela: ausente = efetivada; só CLEARED e PENDING (texto vazio e minúsculas não). */
		String firstInstallmentStatus) {

	public CreateInstallmentPurchaseRequest {
		description = description == null ? null : description.strip();
		notes = notes == null || notes.isBlank() ? null : notes.strip();
	}

}
