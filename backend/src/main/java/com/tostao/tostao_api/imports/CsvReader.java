package com.tostao.tostao_api.imports;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê um CSV de extrato: RFC 4180 (aspas, separador e quebra de linha dentro de aspas, {@code \r\n} ou {@code \n}), com o
 * separador {@code ;} ou {@code ,} achado pelo cabeçalho. O cabeçalho é a primeira linha que tem as colunas de data,
 * descrição e valor (por nome, sem diferença de maiúsculas e acentos); o que vem antes (resumos) é ignorado, assim como
 * as linhas vazias. Uma coluna opcional {@code id} ou {@code identificador} vale como identificador do banco.
 */
final class CsvReader {

	private static final Set<String> DATE = Set.of("data", "date");
	private static final Set<String> DESCRIPTION = Set.of("descricao", "historico", "lancamento", "memo", "description");
	private static final Set<String> AMOUNT = Set.of("valor", "amount");
	private static final Set<String> IDENTIFIER = Set.of("id", "identificador");

	private static final Pattern BR_DATE = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})");
	private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

	private CsvReader() {
	}

	/** Uma linha do arquivo: o número da linha física em que começa e as células. */
	private record Record(int line, List<String> cells) {
	}

	/** As posições das colunas no cabeçalho ({@code identifier} é -1 quando não há). */
	private record Header(int row, int date, int description, int amount, int identifier) {
	}

	/** As linhas lidas, ou vazio se nenhum separador deixou achar um cabeçalho reconhecido. */
	static Optional<List<RawLine>> read(String text) {
		for (char delimiter : new char[] { ';', ',' }) {
			List<Record> records = records(text, delimiter);
			Header header = findHeader(records);
			if (header != null) {
				return Optional.of(lines(records, header));
			}
		}
		return Optional.empty();
	}

	private static Header findHeader(List<Record> records) {
		for (int row = 0; row < records.size(); row++) {
			List<String> cells = records.get(row).cells();
			int date = -1;
			int description = -1;
			int amount = -1;
			int identifier = -1;
			for (int column = 0; column < cells.size(); column++) {
				String name = ImportKeys.normalize(cells.get(column));
				if (date < 0 && DATE.contains(name)) {
					date = column;
				}
				else if (description < 0 && DESCRIPTION.contains(name)) {
					description = column;
				}
				else if (amount < 0 && AMOUNT.contains(name)) {
					amount = column;
				}
				else if (identifier < 0 && IDENTIFIER.contains(name)) {
					identifier = column;
				}
			}
			if (date >= 0 && description >= 0 && amount >= 0) {
				return new Header(row, date, description, amount, identifier);
			}
		}
		return null;
	}

	private static List<RawLine> lines(List<Record> records, Header header) {
		int needed = Math.max(header.date(), Math.max(header.description(), header.amount()));
		List<RawLine> lines = new ArrayList<>();
		for (int row = header.row() + 1; row < records.size(); row++) {
			Record record = records.get(row);
			List<String> cells = record.cells();
			if (cells.size() <= needed) {
				lines.add(new RawLine(record.line(), null, "", null, null, List.of("linha incompleta")));
				continue;
			}
			List<String> problems = new ArrayList<>();
			LocalDate date = date(cells.get(header.date()).strip());
			if (date == null) {
				problems.add("data inválida");
			}
			Money.Result money = Money.csv(cells.get(header.amount()));
			if (money.problem() != null) {
				problems.add(money.problem());
			}
			String identifier = header.identifier() >= 0 && header.identifier() < cells.size()
					? cells.get(header.identifier()).strip() : null;
			lines.add(new RawLine(record.line(), date, cells.get(header.description()), money.cents(),
					identifier == null || identifier.isEmpty() ? null : identifier, problems));
		}
		return lines;
	}

	/** {@code dd/MM/yyyy} ou {@code yyyy-MM-dd}; nulo se não for uma data real. */
	private static LocalDate date(String text) {
		Matcher br = BR_DATE.matcher(text);
		if (br.matches()) {
			return of(Integer.parseInt(br.group(3)), Integer.parseInt(br.group(2)), Integer.parseInt(br.group(1)));
		}
		Matcher iso = ISO_DATE.matcher(text);
		if (iso.matches()) {
			return of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
		}
		return null;
	}

	private static LocalDate of(int year, int month, int day) {
		if (month < 1 || month > 12 || day < 1 || day > YearMonth.of(year, month).lengthOfMonth()) {
			return null;
		}
		return LocalDate.of(year, month, day);
	}

	/** Separa em linhas e células. Linhas totalmente em branco são descartadas. */
	private static List<Record> records(String text, char delimiter) {
		List<Record> records = new ArrayList<>();
		List<String> cells = new ArrayList<>();
		StringBuilder cell = new StringBuilder();
		boolean quoted = false;
		boolean cellStart = true;
		int physicalLine = 1;
		int recordLine = 1;
		int n = text.length();
		for (int i = 0; i < n; i++) {
			char c = text.charAt(i);
			if (quoted) {
				if (c == '"') {
					if (i + 1 < n && text.charAt(i + 1) == '"') {
						cell.append('"');
						i++;
					}
					else {
						quoted = false;
					}
				}
				else {
					if (c == '\n') {
						physicalLine++;
					}
					cell.append(c);
				}
				continue;
			}
			if (c == '"' && cellStart) {
				quoted = true;
				cellStart = false;
			}
			else if (c == delimiter) {
				cells.add(cell.toString());
				cell.setLength(0);
				cellStart = true;
			}
			else if (c == '\r' || c == '\n') {
				if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
					i++;
				}
				cells.add(cell.toString());
				cell.setLength(0);
				addIfNotBlank(records, recordLine, cells);
				cells = new ArrayList<>();
				cellStart = true;
				physicalLine++;
				recordLine = physicalLine;
			}
			else {
				cell.append(c);
				cellStart = false;
			}
		}
		cells.add(cell.toString());
		addIfNotBlank(records, recordLine, cells);
		return records;
	}

	private static void addIfNotBlank(List<Record> records, int line, List<String> cells) {
		if (cells.stream().anyMatch(cell -> !cell.isBlank())) {
			records.add(new Record(line, cells));
		}
	}

}
