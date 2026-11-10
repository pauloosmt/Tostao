package com.tostao.tostao_api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** O leitor de OFX 1 (SGML) e 2 (XML), sem parser de XML (FR-002, FR-003, FR-022). */
class OfxReaderTest {

	private static List<RawLine> read(String file) {
		return OfxReader.read(Statements.text(file));
	}

	@Test
	void readsAnOfx1StatementWhereTheLeavesAreNotClosed() {
		List<RawLine> lines = read("banco.ofx");

		assertThat(lines).hasSize(4);
		assertThat(lines).extracting(RawLine::line).containsExactly(1, 2, 3, 4);
		assertThat(lines).extracting(RawLine::date).containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6),
				LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 6));
		assertThat(lines).extracting(RawLine::cents).containsExactly(500000L, -2350L, -18090L, -2350L);
		assertThat(lines).extracting(RawLine::identifier).containsExactly("1", "2", "3", "4");
		assertThat(lines).extracting(RawLine::description).containsExactly("SALARIO EMPRESA XYZ", "PADARIA DO ZE",
				"MERCADO CENTRAL", "PADARIA DO ZE");
		assertThat(lines).allSatisfy(line -> assertThat(line.problems()).isEmpty());
	}

	@Test
	void readsAnOfx2XmlStatementWithEntitiesAndBothNameAndMemo() {
		List<RawLine> lines = read("banco2.ofx");

		assertThat(lines).hasSize(4);
		assertThat(lines).extracting(RawLine::cents).containsExactly(500000L, -2350L, -18090L, -2350L);
		assertThat(lines).extracting(RawLine::description).containsExactly("SALÁRIO EMPRESA XYZ",
				"PADARIA DO ZÉ & CIA - COMPRA CARTAO", "MERCADO CENTRAL", "PADARIA DO ZÉ & CIA - COMPRA CARTAO");
		assertThat(lines).allSatisfy(line -> assertThat(line.problems()).isEmpty());
	}

	@Test
	void readsACreditCardStatementTheSameWay() {
		List<RawLine> lines = read("cartao.ofx");

		assertThat(lines).extracting(RawLine::cents).containsExactly(-8990L, -125000L, 150000L);
		assertThat(lines).extracting(RawLine::identifier).containsExactly("C1", "C2", "C3");
		assertThat(lines.get(1).description()).isEqualTo("LOJA ELETRO 02/10");
	}

	@Test
	void theDateIsTheOneWrittenWithoutTimeOrTimeZone() {
		String ofx = "<OFX><STMTTRN><DTPOSTED>20261231235959.000[-3:BRT]<TRNAMT>-1.00<FITID>a<MEMO>x</STMTTRN>"
				+ "<STMTTRN><DTPOSTED>20270101000000[+9:JST]<TRNAMT>-1.00<FITID>b<MEMO>y</STMTTRN></OFX>";

		assertThat(OfxReader.read(ofx)).extracting(RawLine::date).containsExactly(LocalDate.of(2026, 12, 31),
				LocalDate.of(2027, 1, 1));
	}

	@Test
	void badDatesAndAmountsAreProblemsOnThatLineOnly() {
		String ofx = "<OFX><STMTTRN><DTPOSTED>20261301<TRNAMT>-1.00<FITID>a<MEMO>mes 13</STMTTRN>"
				+ "<STMTTRN><DTPOSTED>20260230<TRNAMT>-1.00<FITID>b<MEMO>30 de fevereiro</STMTTRN>"
				+ "<STMTTRN><DTPOSTED>2026<TRNAMT>-1.00<FITID>c<MEMO>curta</STMTTRN>"
				+ "<STMTTRN><TRNAMT>-1.00<FITID>d<MEMO>sem data</STMTTRN>"
				+ "<STMTTRN><DTPOSTED>20261106<TRNAMT>abc<FITID>e<MEMO>valor ruim</STMTTRN>"
				+ "<STMTTRN><DTPOSTED>20261106<FITID>f<MEMO>sem valor</STMTTRN>"
				+ "<STMTTRN><DTPOSTED>20261106<TRNAMT>0.00<FITID>g<MEMO>zero</STMTTRN>"
				+ "<STMTTRN><DTPOSTED>20261106<TRNAMT>-2.00<FITID>h<MEMO>boa</STMTTRN></OFX>";

		List<RawLine> lines = OfxReader.read(ofx);

		assertThat(lines).hasSize(8);
		assertThat(lines.get(0).problems()).containsExactly("data inválida");
		assertThat(lines.get(1).problems()).containsExactly("data inválida");
		assertThat(lines.get(2).problems()).containsExactly("data inválida");
		assertThat(lines.get(3).problems()).containsExactly("data ausente");
		assertThat(lines.get(4).problems()).containsExactly("valor ilegível");
		assertThat(lines.get(5).problems()).containsExactly("valor ausente");
		assertThat(lines.get(6).problems()).containsExactly("valor zero");
		assertThat(lines.get(7).problems()).isEmpty();
		assertThat(lines.get(7).cents()).isEqualTo(-200L);
	}

	@Test
	void nameAndMemoAreJoinedOnlyWhenTheyDiffer() {
		String ofx = "<OFX><STMTTRN><DTPOSTED>20261106<TRNAMT>-1.00<FITID>a<NAME>Padaria<MEMO>PADARIA</STMTTRN>"
				+ "<STMTTRN><DTPOSTED>20261106<TRNAMT>-1.00<FITID>b<NAME>Padaria<MEMO>Compra no débito</STMTTRN>"
				+ "<STMTTRN><DTPOSTED>20261106<TRNAMT>-1.00<FITID>c<NAME>Só nome</STMTTRN>"
				+ "<STMTTRN><DTPOSTED>20261106<TRNAMT>-1.00<FITID>d</STMTTRN></OFX>";

		assertThat(OfxReader.read(ofx)).extracting(RawLine::description).containsExactly("Padaria",
				"Padaria - Compra no débito", "Só nome", "");
	}

	@Test
	void blocksOutsideTheTransactionAreIgnored() {
		String ofx = "<OFX><BANKACCTFROM><ACCTID>999<MEMO>fora</BANKACCTFROM><NAME>fora</NAME>"
				+ "<STMTTRN><DTPOSTED>20261106<TRNAMT>-1.00<FITID>a<MEMO>dentro</STMTTRN>"
				+ "<LEDGERBAL><BALAMT>10.00<DTASOF>20261110</LEDGERBAL></OFX>";

		assertThat(OfxReader.read(ofx)).singleElement().satisfies(line -> {
			assertThat(line.description()).isEqualTo("dentro");
			assertThat(line.identifier()).isEqualTo("a");
		});
	}

	@Test
	void entitiesAreNeverResolvedOrExpanded() {
		List<RawLine> lines = read("entidade.ofx");

		assertThat(lines).hasSize(3);
		// as referências a entidades declaradas ficam como texto: nada do arquivo local nem de endereço externo
		assertThat(lines).extracting(RawLine::description).containsExactly("&xxe;", "&remoto;", "&d;");
		assertThat(lines).extracting(RawLine::cents).containsExactly(-1000L, -1100L, -1200L);
	}

	@Test
	void onlyThePredefinedAndNumericEntitiesAreReplaced() {
		assertThat(OfxReader.decode("A &amp; B &lt;c&gt; &quot;d&quot; &apos;e&apos;")).isEqualTo("A & B <c> \"d\" 'e'");
		assertThat(OfxReader.decode("&#65;&#x42;&#233;")).isEqualTo("ABé");
		// uma vez, sem recursão: &amp;lt; vira &lt; e fica
		assertThat(OfxReader.decode("&amp;lt;")).isEqualTo("&lt;");
		assertThat(OfxReader.decode("&desconhecida; & solto &#; &#x; &#99999999;")).isEqualTo("&desconhecida; & solto &#; &#x; &#99999999;");
		assertThat(OfxReader.decode("sem entidade")).isEqualTo("sem entidade");
	}

	@Test
	void garbageAndTruncatedInputNeverBreakTheReader() {
		assertThat(OfxReader.read("")).isEmpty();
		assertThat(OfxReader.read("<")).isEmpty();
		assertThat(OfxReader.read("<<<<<<>>>>>>")).isEmpty();
		assertThat(OfxReader.read("<OFX><STMTTRN><DTPOSTED>2026")).hasSize(1);
		assertThat(OfxReader.read("sem marca nenhuma")).isEmpty();
		assertThat(OfxReader.read("<OFX><STMTTRN><MEMO>a<STMTTRN><MEMO>b</STMTTRN></OFX>")).hasSize(2);
		assertThat(OfxReader.read("<!-- <STMTTRN> --><?xml <STMTTRN> ?><OFX></OFX>")).isEmpty();
	}

}
