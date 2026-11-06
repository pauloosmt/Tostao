package com.tostao.tostao_api.transaction.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.tostao.tostao_api.account.MoneyLimits;
import com.tostao.tostao_api.transaction.TransactionType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cadastro de lançamento. O valor chega sempre positivo, em centavos inteiros; o sinal vem do tipo.
 * Receita e despesa exigem {@code categoryId}; a transferência exige {@code destinationAccountId} e não
 * aceita categoria (regras conferidas no serviço). Campos fora desta lista, como o workspace, quem criou, o
 * grupo da transferência ou as datas de criação e exclusão, são recusados.
 */
public record CreateTransactionRequest(
		@NotNull(message = "é obrigatório")
		TransactionType type,

		@NotNull(message = "é obrigatório")
		UUID accountId,

		UUID destinationAccountId,

		@NotNull(message = "é obrigatório")
		@Min(value = 1, message = "deve ser de 1 a 1.000.000.000.000 centavos")
		@Max(value = MoneyLimits.MAX_CENTS, message = "deve ser de 1 a 1.000.000.000.000 centavos")
		Long amountCents,

		@NotNull(message = "é obrigatório")
		LocalDate occurredOn,

		@NotBlank(message = "é obrigatório")
		@Size(max = 200, message = "deve ter no máximo 200 caracteres")
		String description,

		@Size(max = 1000, message = "deve ter no máximo 1.000 caracteres")
		String notes,

		UUID categoryId) {

	public CreateTransactionRequest {
		description = description == null ? null : description.strip();
		notes = notes == null || notes.isBlank() ? null : notes.strip();
	}

}
