package com.tostao.tostao_api.invoice;

/**
 * Situação de uma fatura: {@code OPEN} até o dia do fechamento; {@code PAID} quando já fechou e não resta
 * valor; {@code OVERDUE} quando já fechou, o vencimento passou e resta valor; {@code CLOSED} nos demais casos.
 */
public enum InvoiceStatus {
	OPEN, CLOSED, PAID, OVERDUE
}
