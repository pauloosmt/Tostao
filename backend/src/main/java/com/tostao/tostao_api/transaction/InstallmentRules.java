package com.tostao.tostao_api.transaction;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.tostao.tostao_api.common.error.FieldValidationException;

/**
 * Regras da compra parcelada que não dependem de banco nem de Spring: divisão do total em centavos exatos,
 * calendário das parcelas, faixa de datas e texto da descrição. Ficam aqui para serem provadas por teste
 * unitário puro. O número de parcelas (2 a 48) e o teto do total já são conferidos pelo DTO de entrada.
 */
public final class InstallmentRules {

	public static final int MIN_COUNT = 2;
	public static final int MAX_COUNT = 48;
	/** Descrição base: com o sufixo " (48/48)" (8 caracteres) cabe nos 200 da descrição de um lançamento. */
	public static final int MAX_DESCRIPTION = 190;

	private InstallmentRules() {
	}

	/**
	 * Divide o total em {@code count} parcelas de centavos inteiros: cada uma vale o total dividido pelo número de
	 * parcelas (para baixo) e o resto vai, um centavo por parcela, a partir da primeira. A soma é sempre o total e
	 * nenhuma parcela difere de outra em mais de 1 centavo.
	 */
	public static long[] split(long totalCents, int count) {
		long base = totalCents / count;
		long rest = totalCents % count;
		long[] parts = new long[count];
		for (int i = 0; i < count; i++) {
			parts[i] = i < rest ? base + 1 : base;
		}
		return parts;
	}

	/**
	 * A data de cada parcela: a primeira somada a 0, 1, 2... meses, <b>sempre a partir da primeira</b>. O dia é
	 * limitado ao último do mês, mas o ajuste não acumula (31/01, 28/02, 31/03, 30/04).
	 */
	public static List<LocalDate> dates(LocalDate first, int count) {
		List<LocalDate> dates = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			dates.add(first.plusMonths(i));
		}
		return dates;
	}

	/** Cada parcela vale pelo menos 1 centavo: o total não pode ser menor que o número de parcelas. */
	public static void requireTotal(long totalCents, int count) {
		if (totalCents < count) {
			throw new FieldValidationException("totalCents", "deve ser de pelo menos 1 centavo por parcela");
		}
	}

	/** A primeira e a última parcelas ficam entre 2000-01-01 e 2100-12-31 (a mesma faixa dos lançamentos). */
	public static void requireDatesInRange(LocalDate first, int count) {
		LocalDate last = first.plusMonths(count - 1L);
		if (first.isBefore(TransactionRules.MIN_DATE) || last.isAfter(TransactionRules.MAX_DATE)) {
			throw new FieldValidationException("firstOccurredOn",
					"a primeira e a última parcela devem cair entre 2000 e 2100");
		}
	}

	/** A descrição de uma parcela: a base seguida de " (N/T)". */
	public static String describe(String base, int number, int count) {
		return base + suffix(number, count);
	}

	/**
	 * A descrição base de um plano a partir da descrição de uma das parcelas: tira o sufixo " (N/T)" quando a
	 * descrição termina exatamente com o sufixo esperado dessa parcela. Se a descrição foi editada à mão e não
	 * termina assim, devolve-a como está.
	 */
	public static String baseDescription(String description, int number, int count) {
		String suffix = suffix(number, count);
		return description.endsWith(suffix) ? description.substring(0, description.length() - suffix.length())
				: description;
	}

	private static String suffix(int number, int count) {
		return " (" + number + "/" + count + ")";
	}

}
