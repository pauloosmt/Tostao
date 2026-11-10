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
 * Edição de lançamento: a edição é completa (campos opcionais ausentes limpam o valor). O {@code type} é
 * opcional só para o cliente poder devolver o objeto inteiro; se vier diferente do atual, a edição é
 * recusada. Na transferência, {@code accountId} é sempre a origem e {@code destinationAccountId} o destino,
 * qualquer que seja a ponta endereçada. Campos fora desta lista são recusados.
 */
public record UpdateTransactionRequest(
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

		UUID categoryId,

		/** Estado: ausente = mantém o atual; só CLEARED e PENDING são aceitos (texto vazio e minúsculas não). */
		String status,

		/** Efetivar sozinho quando a data chegar: ausente = mantém o atual; só vale para lançamento pendente. */
		Boolean autoClear) {

	/** Sem o marcador: o marcador ausente mantém o atual. Mantém compiláveis os usos anteriores à spec 011. */
	public UpdateTransactionRequest(TransactionType type, UUID accountId, UUID destinationAccountId, Long amountCents,
			LocalDate occurredOn, String description, String notes, UUID categoryId, String status) {
		this(type, accountId, destinationAccountId, amountCents, occurredOn, description, notes, categoryId, status, null);
	}

	public UpdateTransactionRequest {
		description = description == null ? null : description.strip();
		notes = notes == null || notes.isBlank() ? null : notes.strip();
	}

}
