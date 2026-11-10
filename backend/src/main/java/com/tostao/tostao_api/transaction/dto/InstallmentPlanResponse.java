package com.tostao.tostao_api.transaction.dto;

import java.util.List;
import java.util.UUID;

/**
 * O plano de uma compra parcelada: o conjunto das parcelas com o mesmo grupo. {@code description} é a descrição
 * base (sem o sufixo da parcela) e {@code installmentCount} o total original de parcelas, mesmo que alguma tenha
 * sido excluída. Os totais são positivos e somam só as parcelas não excluídas: {@code totalCents} é a soma de
 * todas, {@code clearedCents} a das efetivadas e {@code pendingCents} a das pendentes.
 */
public record InstallmentPlanResponse(
		UUID groupId,
		String description,
		int installmentCount,
		UUID accountId,
		UUID categoryId,
		long totalCents,
		long clearedCents,
		long pendingCents,
		List<TransactionResponse> installments) {
}
