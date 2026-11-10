package com.tostao.tostao_api.transaction;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Página e filtros da listagem. Os filtros são opcionais e combinados com "e". Os limites ({@code page}
 * a partir de 1, {@code size} de 1 a 100, {@code q} com até 100 caracteres, período não invertido) são
 * conferidos pelo serviço, que aponta o campo inválido.
 */
public record TransactionFilter(
		int page,
		int size,
		LocalDate from,
		LocalDate to,
		UUID accountId,
		UUID categoryId,
		TransactionType type,
		String q,
		TransactionStatus status,
		String uncategorized) {

	/** Sem filtro de estado: lista pendentes e efetivados. */
	public TransactionFilter(int page, int size, LocalDate from, LocalDate to, UUID accountId, UUID categoryId,
			TransactionType type, String q) {
		this(page, size, from, to, accountId, categoryId, type, q, null, null);
	}

	/** Sem o filtro de "sem categoria". */
	public TransactionFilter(int page, int size, LocalDate from, LocalDate to, UUID accountId, UUID categoryId,
			TransactionType type, String q, TransactionStatus status) {
		this(page, size, from, to, accountId, categoryId, type, q, status, null);
	}

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 100;
	public static final int MAX_TEXT = 100;

}
