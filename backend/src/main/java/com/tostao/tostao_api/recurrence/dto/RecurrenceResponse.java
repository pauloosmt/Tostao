package com.tostao.tostao_api.recurrence.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.tostao.tostao_api.recurrence.RecurrenceFrequency;
import com.tostao.tostao_api.transaction.TransactionType;

/**
 * Resumo de uma recorrência. {@code amountCents} é sempre positivo (o sinal vem do tipo). {@code ended} diz se foi
 * encerrada; {@code nextOccurrenceOn} é a menor data entre as ocorrências pendentes não excluídas de hoje em diante
 * (nula se não há).
 */
public record RecurrenceResponse(
		UUID id,
		UUID accountId,
		UUID destinationAccountId,
		UUID categoryId,
		TransactionType type,
		long amountCents,
		String description,
		String notes,
		RecurrenceFrequency frequency,
		LocalDate startOn,
		LocalDate endOn,
		boolean autoClear,
		boolean ended,
		Instant endedAt,
		LocalDate nextOccurrenceOn,
		UUID createdBy,
		Instant createdAt) {
}
