package com.tostao.tostao_api.imports;

/**
 * Texto de valor de extrato para centavos inteiros exatos, sem ponto flutuante e sem arredondar em silêncio. Aceita
 * vírgula ou ponto decimal, sinal {@code +} ou {@code -} e o prefixo {@code R$}.
 *
 * <p>
 * No OFX, que não usa separador de milhar, o último ponto ou vírgula é o decimal. No CSV, com os dois separadores o último
 * é o decimal; com um só tipo, seguido de exatamente três dígitos vale como milhar ({@code 1.234} = 1.234,00) e seguido
 * de um ou dois como decimal ({@code 12,5} = 12,50). Mais de duas casas decimais, zero e fora de 1 a 1.000.000.000.000
 * centavos em módulo são problema da linha.
 */
final class Money {

	/** O valor em centavos com sinal, ou o motivo de não ter sido lido (exatamente um dos dois). */
	record Result(Long cents, String problem) {

		static Result ok(long cents) {
			return new Result(cents, null);
		}

		static Result problem(String problem) {
			return new Result(null, problem);
		}
	}

	static final long MAX_CENTS = 1_000_000_000_000L;

	private static final Result UNREADABLE = Result.problem("valor ilegível");

	private Money() {
	}

	static Result ofx(String text) {
		return parse(text, false);
	}

	static Result csv(String text) {
		return parse(text, true);
	}

	private static Result parse(String text, boolean csv) {
		if (text == null) {
			return Result.problem("valor ausente");
		}
		String s = removeNoise(text);
		if (s.isEmpty()) {
			return Result.problem("valor ausente");
		}
		boolean negative = false;
		if (s.charAt(0) == '-' || s.charAt(0) == '+') {
			negative = s.charAt(0) == '-';
			s = s.substring(1);
		}
		if (!s.chars().allMatch(c -> isDigit(c) || c == '.' || c == ',') || s.chars().noneMatch(Money::isDigit)) {
			return UNREADABLE;
		}

		String[] parts = csv ? splitCsv(s) : splitOfx(s);
		if (parts == null) {
			return UNREADABLE;
		}
		String integerPart = parts[0].isEmpty() ? "0" : parts[0];
		String fraction = parts[1];
		if (fraction.length() > 2) {
			return Result.problem("valor com mais de duas casas decimais");
		}
		String digits = integerPart.replaceFirst("^0+(?=.)", "");
		if (digits.length() > 13) {
			return Result.problem("valor acima do limite");
		}
		long cents = Long.parseLong(digits) * 100 + Long.parseLong((fraction + "00").substring(0, 2));
		if (cents == 0) {
			return Result.problem("valor zero");
		}
		if (cents > MAX_CENTS) {
			return Result.problem("valor acima do limite");
		}
		return Result.ok(negative ? -cents : cents);
	}

	/** OFX: o último separador é o decimal; antes dele só dígitos. Devolve {inteira, fração} ou nulo. */
	private static String[] splitOfx(String s) {
		int last = Math.max(s.lastIndexOf('.'), s.lastIndexOf(','));
		if (last < 0) {
			return new String[] { s, "" };
		}
		String integerPart = s.substring(0, last);
		String fraction = s.substring(last + 1);
		if (integerPart.indexOf('.') >= 0 || integerPart.indexOf(',') >= 0 || fraction.isEmpty()) {
			return null;
		}
		return new String[] { integerPart, fraction };
	}

	/** CSV: ver a regra na descrição da classe. Devolve {inteira, fração} ou nulo. */
	private static String[] splitCsv(String s) {
		int last = Math.max(s.lastIndexOf('.'), s.lastIndexOf(','));
		if (last < 0) {
			return new String[] { s, "" };
		}
		char sep = s.charAt(last);
		char other = sep == '.' ? ',' : '.';
		boolean repeated = s.indexOf(sep) != last;
		String after = s.substring(last + 1);
		if (s.indexOf(other) >= 0) {
			// os dois tipos: o último é o decimal (não se repete) e o outro é milhar, só antes dele
			if (repeated || s.lastIndexOf(other) > last || after.isEmpty()) {
				return null;
			}
			String integerPart = thousands(s.substring(0, last), other);
			return integerPart == null ? null : new String[] { integerPart, after };
		}
		if (repeated || after.length() == 3) {
			// um só tipo, em grupos de três dígitos: milhar
			String integerPart = thousands(s, sep);
			return integerPart == null ? null : new String[] { integerPart, "" };
		}
		if (after.isEmpty()) {
			return null;
		}
		return new String[] { s.substring(0, last), after };
	}

	/**
	 * Remove o separador de milhar, conferindo os grupos: o primeiro tem de 1 a 3 dígitos e não começa com zero, os
	 * demais têm exatamente três. Devolve nulo se não for um número agrupado assim.
	 */
	private static String thousands(String text, char sep) {
		if (text.indexOf(sep) < 0) {
			return text;
		}
		String[] groups = text.split("\\" + sep, -1);
		if (groups[0].isEmpty() || groups[0].length() > 3 || groups[0].charAt(0) == '0') {
			return null;
		}
		StringBuilder out = new StringBuilder(groups[0]);
		for (int i = 1; i < groups.length; i++) {
			if (groups[i].length() != 3) {
				return null;
			}
			out.append(groups[i]);
		}
		return out.toString();
	}

	/** Tira os espaços (inclusive o inseparável) e o prefixo {@code R$}, em qualquer posição. */
	private static String removeNoise(String text) {
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (Character.isWhitespace(c) || c == ' ' || c == ' ') {
				continue;
			}
			if ((c == 'R' || c == 'r') && i + 1 < text.length() && text.charAt(i + 1) == '$') {
				i++;
				continue;
			}
			out.append(c);
		}
		return out.toString();
	}

	private static boolean isDigit(int c) {
		return c >= '0' && c <= '9';
	}

}
