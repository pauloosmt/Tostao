package com.tostao.tostao_api.recurrence.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.tostao.tostao_api.recurrence.RecurrenceFrequency;
import com.tostao.tostao_api.transaction.TransactionType;
import com.tostao.tostao_api.transaction.dto.TransactionResponse;

/**
 * Uma recorrência com as suas ocorrências não excluídas, em ordem de número: os mesmos campos do resumo
 * ({@link RecurrenceResponse}) mais os totais e a lista. Os totais são positivos e somam só as ocorrências da lista:
 * {@code totalCents} é a soma de todas, {@code clearedCents} a das efetivadas e {@code pendingCents} a das pendentes.
 */
public record RecurrenceDetailResponse(
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
		Instant createdAt,
		long totalCents,
		long clearedCents,
		long pendingCents,
		List<TransactionResponse> occurrences) {

	/** O detalhe a partir do resumo e da lista de ocorrências, com os totais somados dela. */
	public static RecurrenceDetailResponse of(RecurrenceResponse r, long totalCents, long clearedCents, long pendingCents,
			List<TransactionResponse> occurrences) {
		return new RecurrenceDetailResponse(r.id(), r.accountId(), r.destinationAccountId(), r.categoryId(), r.type(), r.amountCents(),
				r.description(), r.notes(), r.frequency(), r.startOn(), r.endOn(), r.autoClear(), r.ended(), r.endedAt(),
				r.nextOccurrenceOn(), r.createdBy(), r.createdAt(), totalCents, clearedCents, pendingCents, occurrences);
	}

}
