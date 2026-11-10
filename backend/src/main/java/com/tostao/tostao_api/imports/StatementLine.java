package com.tostao.tostao_api.imports;

import java.time.LocalDate;
import java.util.List;

/**
 * Uma linha do extrato já normalizada: descrição limpa e cortada, data dentro do intervalo, valor em centavos com sinal e
 * a chave de importação. {@code key} é nula quando a linha tem problema; {@code problems} é vazia quando está boa.
 */
public record StatementLine(int line, LocalDate occurredOn, String description, Long amountCents, String key,
		List<String> problems) {

	public boolean valid() {
		return problems.isEmpty();
	}

}
