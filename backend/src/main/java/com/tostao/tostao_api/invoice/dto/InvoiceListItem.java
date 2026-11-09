package com.tostao.tostao_api.invoice.dto;

import java.time.LocalDate;

import com.tostao.tostao_api.invoice.InvoiceStatus;

/** Uma fatura na lista: os mesmos números da consulta individual, sem os lançamentos. */
public record InvoiceListItem(
		String month,
		LocalDate periodStart,
		LocalDate closingDate,
		LocalDate dueDate,
		long totalCents,
		long paidCents,
		long remainingCents,
		InvoiceStatus status) {
}
