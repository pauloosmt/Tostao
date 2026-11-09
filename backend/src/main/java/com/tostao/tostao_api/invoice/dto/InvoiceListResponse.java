package com.tostao.tostao_api.invoice.dto;

import java.util.List;

/** Faturas de um intervalo de meses de vencimento, do mais recente para o mais antigo. */
public record InvoiceListResponse(List<InvoiceListItem> invoices) {
}
