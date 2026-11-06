package com.tostao.tostao_api.transaction.dto;

import java.util.List;

/** Uma página de lançamentos, com o total de itens e de páginas do filtro aplicado. */
public record TransactionPageResponse(
		List<TransactionResponse> items,
		int page,
		int size,
		long totalItems,
		int totalPages) {
}
