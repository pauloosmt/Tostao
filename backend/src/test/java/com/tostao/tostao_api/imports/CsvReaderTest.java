package com.tostao.tostao_api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** O leitor de CSV: separador pelo cabeçalho, colunas por nome, aspas e resumos antes do cabeçalho (FR-002, FR-003). */
class CsvReaderTest {

	private static List<RawLine> read(String text) {
		return CsvReader.read(text).orElseThrow(() -> new AssertionError("cabeçalho não reconhecido"));
	}

	@Test
	void readsASemicolonFileWithBrazilianNumbersAndAQuotedSeparator() {
		List<RawLine> lines = read(Statements.text("banco.csv"));

		assertThat(lines).hasSize(4);
		assertThat(lines).extracting(RawLine::date).containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6),
				LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 6));
		assertThat(lines).extracting(RawLine::cents).containsExactly(500000L, -2350L, -18090L, -2350L);
		assertThat(lines).extracting(RawLine::description).containsExactly("SALARIO EMPRESA XYZ", "PADARIA DO ZE",
				"MERCADO; CENTRAL", "PADARIA DO ZE");
		assertThat(lines).extracting(RawLine::line).containsExactly(2, 3, 4, 5);
		assertThat(lines).extracting(RawLine::identifier).containsOnlyNulls();
		assertThat(lines).allSatisfy(line -> assertThat(line.problems()).isEmpty());
	}

	@Test
	void readsACommaFileWithIsoDatesAnIdColumnAndEnglishNames() {
		List<RawLine> lines = read(Statements.text("banco-virgula.csv"));

		assertThat(lines).hasSize(3);
		assertThat(lines).extracting(RawLine::date).containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6),
				LocalDate.of(2026, 11, 7));
		assertThat(lines).extracting(RawLine::cents).containsExactly(500000L, -2350L, -18090L);
		assertThat(lines).extracting(RawLine::identifier).containsExactly("A1", "A2", "A3");
		assertThat(lines.get(2).description()).isEqualTo("MERCADO, CENTRAL");
	}

	@Test
	void skipsTheSummaryBeforeTheHeaderAndBlankLines() {
		List<RawLine> lines = read(Statements.text("resumo.csv"));

		// duas linhas boas e a linha "Saldo final", que vem como linha com problema
		assertThat(lines).hasSize(3);
		assertThat(lines.get(0).line()).isEqualTo(6);
		assertThat(lines.get(0).cents()).isEqualTo(500000L);
		assertThat(lines.get(1).cents()).isEqualTo(-2350L);
		assertThat(lines.get(1).description()).isEqualTo("PADARIA DO ZE");
		assertThat(lines.get(2).problems()).containsExactly("data inválida");
	}

	@Test
	void headersAreMatchedWithoutCaseOrAccents() {
		for (String header : new String[] { "DATA;DESCRIÇÃO;VALOR", "data;descricao;valor", "Date;Description;Amount",
				"Data;Histórico;Valor", "Data;Lançamento;Valor", "data;MEMO;valor", " Data ; Descrição ; Valor " }) {
			List<RawLine> lines = read(header + "\n05/11/2026;Teste;10,00\n");

			assertThat(lines).as(header).singleElement().satisfies(line -> assertThat(line.cents()).isEqualTo(1000L));
		}
	}

	@Test
	void columnsMayComeInAnyOrderWithExtraColumns() {
		List<RawLine> lines = read("Saldo;Valor;Tipo;Descrição;Data;Identificador\n1,00;-9,90;DEB;Café;07/11/2026;ID-77\n");

		assertThat(lines).singleElement().satisfies(line -> {
			assertThat(line.cents()).isEqualTo(-990L);
			assertThat(line.description()).isEqualTo("Café");
			assertThat(line.date()).isEqualTo(LocalDate.of(2026, 11, 7));
			assertThat(line.identifier()).isEqualTo("ID-77");
		});
	}

	@Test
	void handlesQuotesEscapedQuotesAndLineBreaksInsideQuotes() {
		String csv = "Data;Descrição;Valor\r\n05/11/2026;\"Ele disse \"\"oi\"\"\";-1,00\r\n"
				+ "06/11/2026;\"duas\nlinhas\";-2,00\r\n07/11/2026;sem aspas;-3,00";

		List<RawLine> lines = read(csv);

		assertThat(lines).extracting(RawLine::description).containsExactly("Ele disse \"oi\"", "duas\nlinhas", "sem aspas");
		assertThat(lines).extracting(RawLine::cents).containsExactly(-100L, -200L, -300L);
		// o número é o da linha física em que o registro começa
		assertThat(lines).extracting(RawLine::line).containsExactly(2, 3, 5);
	}

	@Test
	void aSpreadsheetFormulaIsJustText() {
		List<RawLine> lines = read("Data;Descrição;Valor\n05/11/2026;\"=HYPERLINK(\"\"http://x\"\";\"\"a\"\")\";-1,00\n"
				+ "05/11/2026;+cmd|' /C calc'!A0;-1,00\n05/11/2026;@SUM(A1);-1,00\n");

		assertThat(lines).extracting(RawLine::description).containsExactly("=HYPERLINK(\"http://x\";\"a\")",
				"+cmd|' /C calc'!A0", "@SUM(A1)");
		assertThat(lines).allSatisfy(line -> assertThat(line.problems()).isEmpty());
	}

	@Test
	void badCellsArePerLineProblems() {
		List<RawLine> lines = read("Data;Descrição;Valor\n31/02/2026;Fev;-1,00\n05/11/2026;Valor ruim;abc\n"
				+ "05/11/2026;Curta\n2026-11-05;Boa;-4,00\n05-11-2026;Formato;-1,00\n");

		assertThat(lines).hasSize(5);
		assertThat(lines.get(0).problems()).containsExactly("data inválida");
		assertThat(lines.get(1).problems()).containsExactly("valor ilegível");
		assertThat(lines.get(2).problems()).containsExactly("linha incompleta");
		assertThat(lines.get(3).problems()).isEmpty();
		assertThat(lines.get(4).problems()).containsExactly("data inválida");
	}

	@Test
	void aBlankIdentifierCellMeansNoIdentifier() {
		List<RawLine> lines = read("Data;Descrição;Valor;ID\n05/11/2026;A;-1,00;X1\n05/11/2026;B;-1,00;\n05/11/2026;C;-1,00\n");

		assertThat(lines).extracting(RawLine::identifier).containsExactly("X1", null, null);
	}

	@Test
	void withoutARecognizedHeaderTheFileIsNotACsvOfOurs() {
		assertThat(CsvReader.read("")).isEmpty();
		assertThat(CsvReader.read("apenas texto\noutra linha\n")).isEmpty();
		assertThat(CsvReader.read("Data;Valor\n05/11/2026;1,00\n")).isEmpty();
		assertThat(CsvReader.read("Data;Descrição\n05/11/2026;x\n")).isEmpty();
		assertThat(CsvReader.read("a,b,c\n1,2,3\n")).isEmpty();
	}

	@Test
	void aHeaderOnlyFileHasNoLines() {
		assertThat(read("Data;Descrição;Valor\n")).isEmpty();
	}

}
