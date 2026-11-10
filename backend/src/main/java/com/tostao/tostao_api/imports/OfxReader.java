package com.tostao.tostao_api.imports;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lê os movimentos ({@code STMTTRN}) de um OFX, na versão 1 (SGML, em que as folhas não fecham) ou na 2 (XML). Não usa
 * parser de XML: percorre as marcas, e por isso entidade externa, instrução de processamento e expansão de entidade
 * simplesmente não existem aqui. Declarações {@code <!...>} e instruções {@code <?...?>} são puladas; das entidades de
 * texto só as cinco predefinidas e as numéricas são trocadas, uma vez, sem recursão.
 */
final class OfxReader {

	private OfxReader() {
	}

	static List<RawLine> read(String text) {
		List<RawLine> lines = new ArrayList<>();
		Transaction current = null;
		int count = 0;
		int i = 0;
		int n = text.length();
		while (i < n) {
			int open = text.indexOf('<', i);
			if (open < 0) {
				break;
			}
			int close = text.indexOf('>', open + 1);
			if (close < 0) {
				break;
			}
			String tag = text.substring(open + 1, close);
			i = close + 1;
			if (tag.isEmpty() || tag.charAt(0) == '!' || tag.charAt(0) == '?') {
				continue;
			}
			boolean closing = tag.charAt(0) == '/';
			String name = tagName(closing ? tag.substring(1) : tag);
			if (name.equals("STMTTRN")) {
				if (!closing) {
					if (current != null) {
						lines.add(current.toLine(count));
					}
					current = new Transaction();
					count++;
				}
				else if (current != null) {
					lines.add(current.toLine(count));
					current = null;
				}
				continue;
			}
			if (current == null || closing || tag.endsWith("/")) {
				continue;
			}
			// folha: o valor é o texto até a próxima marca
			int next = text.indexOf('<', i);
			String value = text.substring(i, next < 0 ? n : next);
			switch (name) {
				case "DTPOSTED" -> current.postedAt = clean(value);
				case "TRNAMT" -> current.amount = clean(value);
				case "FITID" -> current.identifier = clean(value);
				case "NAME" -> current.name = clean(value);
				case "MEMO" -> current.memo = clean(value);
				default -> {
				}
			}
		}
		if (current != null) {
			lines.add(current.toLine(count));
		}
		return lines;
	}

	private static String tagName(String tag) {
		int end = 0;
		while (end < tag.length() && !Character.isWhitespace(tag.charAt(end)) && tag.charAt(end) != '/') {
			end++;
		}
		return tag.substring(0, end).toUpperCase(Locale.ROOT);
	}

	private static String clean(String value) {
		String decoded = decode(value).replaceAll("\\s+", " ").strip();
		return decoded.isEmpty() ? null : decoded;
	}

	/** Troca {@code &amp; &lt; &gt; &quot; &apos;} e {@code &#N; &#xH;} por texto, uma vez; o resto fica como está. */
	static String decode(String value) {
		if (value.indexOf('&') < 0) {
			return value;
		}
		StringBuilder out = new StringBuilder(value.length());
		int i = 0;
		while (i < value.length()) {
			char c = value.charAt(i);
			int semicolon = c == '&' ? value.indexOf(';', i) : -1;
			if (semicolon < 0 || semicolon - i > 10) {
				out.append(c);
				i++;
				continue;
			}
			String entity = value.substring(i + 1, semicolon);
			String replacement = switch (entity) {
				case "amp" -> "&";
				case "lt" -> "<";
				case "gt" -> ">";
				case "quot" -> "\"";
				case "apos" -> "'";
				default -> numeric(entity);
			};
			if (replacement == null) {
				out.append(c);
				i++;
			}
			else {
				out.append(replacement);
				i = semicolon + 1;
			}
		}
		return out.toString();
	}

	private static String numeric(String entity) {
		if (entity.length() < 2 || entity.charAt(0) != '#') {
			return null;
		}
		boolean hex = entity.charAt(1) == 'x' || entity.charAt(1) == 'X';
		String digits = entity.substring(hex ? 2 : 1);
		if (digits.isEmpty() || !digits.chars().allMatch(ch -> hex ? Character.digit(ch, 16) >= 0 : (ch >= '0' && ch <= '9'))) {
			return null;
		}
		int code = Integer.parseInt(digits, hex ? 16 : 10);
		return Character.isValidCodePoint(code) && code >= 32 ? new String(Character.toChars(code)) : null;
	}

	/** Os campos de um {@code STMTTRN} enquanto ele é lido. */
	private static final class Transaction {

		String postedAt;
		String amount;
		String identifier;
		String name;
		String memo;

		RawLine toLine(int number) {
			List<String> problems = new ArrayList<>();
			LocalDate date = date(postedAt);
			if (date == null) {
				problems.add(postedAt == null ? "data ausente" : "data inválida");
			}
			Money.Result money = Money.ofx(amount);
			if (money.problem() != null) {
				problems.add(money.problem());
			}
			return new RawLine(number, date, description(), money.cents(), identifier, problems);
		}

		/** O nome e/ou o memorando do banco; os dois, quando diferentes, ficam juntos. */
		private String description() {
			if (name == null) {
				return memo == null ? "" : memo;
			}
			if (memo == null || ImportKeys.normalize(memo).equals(ImportKeys.normalize(name))) {
				return name;
			}
			return name + " - " + memo;
		}

		/** {@code AAAAMMDD} no início, como escrito: horário e fuso depois dele são ignorados. */
		private static LocalDate date(String text) {
			if (text == null || text.length() < 8 || !text.substring(0, 8).chars().allMatch(c -> c >= '0' && c <= '9')) {
				return null;
			}
			int year = Integer.parseInt(text.substring(0, 4));
			int month = Integer.parseInt(text.substring(4, 6));
			int day = Integer.parseInt(text.substring(6, 8));
			if (month < 1 || month > 12 || day < 1 || day > java.time.YearMonth.of(year, month).lengthOfMonth()) {
				return null;
			}
			return LocalDate.of(year, month, day);
		}
	}

}
