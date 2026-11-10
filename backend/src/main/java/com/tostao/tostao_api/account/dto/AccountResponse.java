package com.tostao.tostao_api.account.dto;

import java.time.Instant;
import java.util.UUID;

import com.tostao.tostao_api.account.AccountType;

/**
 * Conta com o saldo calculado. {@code balanceCents} é o saldo inicial somado aos lançamentos não
 * efetivados e não excluídos da conta e nunca é guardado. {@code pendingCents} é a soma dos lançamentos pendentes
 * (com sinal) e {@code projectedBalanceCents} é o saldo previsto: saldo mais pendente. O pendente nunca entra no saldo. Não expõe o {@code workspace_id}.
 */
public record AccountResponse(
		UUID id,
		String name,
		AccountType type,
		String currency,
		long initialBalanceCents,
		long balanceCents,
		long pendingCents,
		long projectedBalanceCents,
		Long creditLimitCents,
		Integer closingDay,
		Integer dueDay,
		boolean archived,
		Instant createdAt,
		Instant updatedAt) {
}
