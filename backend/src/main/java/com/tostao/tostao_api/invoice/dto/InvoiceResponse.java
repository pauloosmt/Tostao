package com.tostao.tostao_api.invoice.dto;

import java.time.LocalDate;
import java.util.List;

import com.tostao.tostao_api.invoice.InvoiceStatus;

/** Fatura de um cartão para um mês de vencimento, com os lançamentos que a compõem. Calculada, nunca guardada. */
public record InvoiceResponse(
		String month,
		LocalDate periodStart,
		LocalDate closingDate,
		LocalDate dueDate,
		long totalCents,
		long paidCents,
		long remainingCents,
		InvoiceStatus status,
		List<InvoiceLine> lines) {
}
