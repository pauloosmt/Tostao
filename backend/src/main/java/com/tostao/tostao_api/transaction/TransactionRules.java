package com.tostao.tostao_api.transaction;

import java.time.LocalDate;

import com.tostao.tostao_api.category.CategoryKind;
import com.tostao.tostao_api.common.error.FieldValidationException;

/**
 * Regras de lançamentos que não dependem de banco nem de Spring: sinal do valor, pontas da
 * transferência, escape da busca por texto, faixa de datas, tipo da categoria e número de páginas. Ficam
 * aqui para serem provadas por teste unitário puro.
 */
public final class TransactionRules {

	static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
	static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);

	private TransactionRules() {
	}

	/**
	 * Valor com sinal a partir do valor positivo que o cliente informa: receita positiva, despesa
	 * negativa. A transferência não tem sinal próprio (cada ponta tem o seu, ver {@link #transferLegs}).
	 */
	public static long signedAmount(TransactionType type, long absoluteCents) {
		return switch (type) {
			case INCOME -> absoluteCents;
			case EXPENSE -> -absoluteCents;
			case TRANSFER -> throw new IllegalArgumentException("A transferência não tem sinal próprio");
		};
	}

	/** As duas pontas de uma transferência: {@code {saída, entrada}} = {@code {-valor, +valor}}. */
	public static long[] transferLegs(long absoluteCents) {
		return new long[] { -absoluteCents, absoluteCents };
	}

	/**
	 * Padrão {@code LIKE ... ESCAPE '\'} para "contém o texto": {@code \}, {@code %} e {@code _} valem
	 * literalmente, nunca como curinga.
	 */
	public static String likePattern(String text) {
		StringBuilder pattern = new StringBuilder("%");
		for (char c : text.toCharArray()) {
			if (c == '\\' || c == '%' || c == '_') {
				pattern.append('\\');
			}
			pattern.append(c);
		}
		return pattern.append('%').toString();
	}

	/** A data de ocorrência fica entre 2000-01-01 e 2100-12-31 (futuro aceito). */
	public static void requireDateInRange(LocalDate date) {
		if (date.isBefore(MIN_DATE) || date.isAfter(MAX_DATE)) {
			throw new FieldValidationException("occurredOn", "deve ser uma data entre 2000 e 2100");
		}
	}

	/** Receita só usa categoria de receita e despesa só usa categoria de despesa. */
	public static void requireCategoryKind(TransactionType type, CategoryKind kind) {
		boolean matches = (type == TransactionType.INCOME && kind == CategoryKind.INCOME)
				|| (type == TransactionType.EXPENSE && kind == CategoryKind.EXPENSE);
		if (!matches) {
			throw new FieldValidationException("categoryId", type == TransactionType.INCOME
					? "deve ser uma categoria de receita"
					: "deve ser uma categoria de despesa");
		}
	}

	/** Total de páginas: o teto de {@code totalItems / size}; zero quando não há itens. */
	public static int totalPages(long totalItems, int size) {
		return (int) ((totalItems + size - 1) / size);
	}

}
