package com.tostao.tostao_api.transaction.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.tostao.tostao_api.transaction.TransactionStatus;
import com.tostao.tostao_api.transaction.TransactionType;

/**
 * Lançamento como a API o entrega. {@code amountCents} sai com sinal: receita e entrada positivas,
 * despesa e saída negativas. Na transferência, {@code transferGroupId} liga as duas pontas e
 * {@code counterpartAccountId} é a conta da outra ponta. {@code status} é o estado (efetivado ou pendente); só o
 * efetivado conta no realizado. Não expõe o {@code workspace_id}.
 */
public record TransactionResponse(
		UUID id,
		UUID accountId,
		TransactionType type,
		long amountCents,
		LocalDate occurredOn,
		String description,
		String notes,
		UUID categoryId,
		UUID transferGroupId,
		UUID counterpartAccountId,
		UUID createdBy,
		Instant createdAt,
		Instant updatedAt,
		TransactionStatus status) {
}
