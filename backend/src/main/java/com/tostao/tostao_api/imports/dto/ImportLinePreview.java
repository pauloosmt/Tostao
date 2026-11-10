package com.tostao.tostao_api.imports.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.tostao.tostao_api.transaction.TransactionType;

/**
 * Uma linha da prévia do extrato. {@code occurredOn}, {@code amountCents} e {@code type} são nulos quando não deu para
 * lê-los; {@code key} é nula quando a linha tem problema. {@code existing}: a chave já existe na conta (inclusive em
 * lançamento excluído). {@code possibleDuplicate}: há na conta um lançamento sem chave, com a mesma data e o mesmo valor
 * (só um aviso). {@code suggestedCategoryId}: a categoria do último lançamento com a mesma descrição, se houver.
 */
public record ImportLinePreview(
		int line,
		LocalDate occurredOn,
		String description,
		Long amountCents,
		TransactionType type,
		String key,
		boolean existing,
		boolean possibleDuplicate,
		UUID suggestedCategoryId,
		List<String> problems) {
}
