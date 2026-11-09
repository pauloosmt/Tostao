package com.tostao.tostao_api.invoice.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Resumo do cartão. {@code owedCents} é o saldo negativo da conta (zero quando o saldo não é negativo);
 * {@code availableCents} é o limite menos o devido (negativo se passou do limite, nulo sem limite);
 * {@code openInvoice} é nula quando o cartão não tem os dois dias cadastrados.
 */
public record CardSummaryResponse(
		UUID accountId,
		String name,
		boolean archived,
		Long limitCents,
		long owedCents,
		Long availableCents,
		OpenInvoice openInvoice) {

	/** A fatura que contém hoje, com o total até agora. */
	public record OpenInvoice(String month, LocalDate periodStart, LocalDate closingDate, LocalDate dueDate,
			long totalCents) {
	}

}
