package com.tostao.tostao_api.transaction.dto;

/** Quantas parcelas pendentes o cancelamento do plano excluiu (zero se não havia nenhuma ou se já foi cancelado). */
public record CancelInstallmentsResponse(int cancelled) {
}
