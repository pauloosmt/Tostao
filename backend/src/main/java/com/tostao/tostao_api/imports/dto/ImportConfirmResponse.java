package com.tostao.tostao_api.imports.dto;

import java.util.List;

import com.tostao.tostao_api.transaction.dto.TransactionResponse;

/** O resultado da confirmação: os lançamentos criados e quantas linhas foram ignoradas por já existirem na conta. */
public record ImportConfirmResponse(List<TransactionResponse> created, int skipped) {
}
