package com.tostao.tostao_api.invoice.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Um lançamento que compõe a fatura. {@code chargeCents} é positivo para compra ou saque e negativo para
 * estorno. Categoria vazia (nula) em transferência ou lançamento sem categoria.
 */
public record InvoiceLine(
		UUID id,
		LocalDate date,
		String description,
		UUID categoryId,
		String categoryName,
		String type,
		long chargeCents) {
}
