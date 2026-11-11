package com.tostao.tostao_api.recurrence.dto;

import java.time.LocalDate;
import java.util.UUID;

import com.tostao.tostao_api.account.MoneyLimits;
import com.tostao.tostao_api.recurrence.RecurrenceFrequency;
import com.tostao.tostao_api.transaction.TransactionType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Criação de uma recorrência. O valor chega positivo, em centavos inteiros; o sinal vem do tipo ({@code INCOME},
 * {@code EXPENSE} ou {@code TRANSFER}). Numa transferência, {@code accountId} é a conta de origem, {@code
 * destinationAccountId} é obrigatório e diferente dela e {@code categoryId} é recusado; em receita e despesa,
 * {@code categoryId} é obrigatório e {@code destinationAccountId} é recusado (a forma é conferida pelo serviço, que aponta
 * o campo). As ocorrências são contadas sempre a partir de {@code startOn}. Campos fora desta lista, como o workspace, quem criou, o estado ou a recorrência de
 * origem, são recusados. As regras que cruzam campos (início e fim) e as de conta e categoria são conferidas no
 * serviço, antes de qualquer gravação.
 */
public record CreateRecurrenceRequest(
		@NotNull(message = "é obrigatório")
		UUID accountId,

		/** Só na transferência: a conta de destino. */
		UUID destinationAccountId,

		/** Só em receita e despesa. */
		UUID categoryId,

		@NotNull(message = "é obrigatório")
		TransactionType type,

		@NotNull(message = "é obrigatório")
		@Min(value = 1, message = "deve ser de 1 a 1.000.000.000.000 centavos")
		@Max(value = MoneyLimits.MAX_CENTS, message = "deve ser de 1 a 1.000.000.000.000 centavos")
		Long amountCents,

		@NotBlank(message = "é obrigatório")
		@Size(max = 200, message = "deve ter no máximo 200 caracteres")
		String description,

		@Size(max = 1000, message = "deve ter no máximo 1.000 caracteres")
		String notes,

		@NotNull(message = "é obrigatório")
		RecurrenceFrequency frequency,

		@NotNull(message = "é obrigatório")
		LocalDate startOn,

		LocalDate endOn,

		/** As ocorrências nascem pendentes com o marcador de efetivação automática: ausente = desligado. */
		Boolean autoClear) {

	/** Receita ou despesa, sem destino (a forma anterior à transferência recorrente). */
	public CreateRecurrenceRequest(UUID accountId, UUID categoryId, TransactionType type, Long amountCents, String description,
			String notes, RecurrenceFrequency frequency, LocalDate startOn, LocalDate endOn, Boolean autoClear) {
		this(accountId, null, categoryId, type, amountCents, description, notes, frequency, startOn, endOn, autoClear);
	}

	public CreateRecurrenceRequest {
		description = description == null ? null : description.strip();
		notes = notes == null || notes.isBlank() ? null : notes.strip();
	}

}
