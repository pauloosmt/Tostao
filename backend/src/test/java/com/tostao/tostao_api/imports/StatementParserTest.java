package com.tostao.tostao_api.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.imports.StatementParser.Format;
import com.tostao.tostao_api.imports.StatementParser.Parsed;

/** O reconhecedor de formato: pelo conteúdo, com decodificação, limites, normalização e chaves (FR-002 a FR-009). */
class StatementParserTest {

	private static Parsed parse(String name) {
		return StatementParser.parse(Statements.bytes(name));
	}

	private static Parsed parseText(String text) {
		return StatementParser.parse(text.getBytes(StandardCharsets.UTF_8));
	}

	private static void refused(byte[] bytes, String reason) {
		assertThatThrownBy(() -> StatementParser.parse(bytes)).isInstanceOf(FieldValidationException.class)
				.hasMessageContaining(reason).satisfies(e -> assertThat(((FieldValidationException) e).field()).isEqualTo("file"));
	}

	// ---- formato -----------------------------------------------------------------------------------------------

	@Test
	void recognizesTheFormatByTheContent() {
		assertThat(parse("banco.ofx").format()).isEqualTo(Format.OFX);
		assertThat(parse("banco2.ofx").format()).isEqualTo(Format.OFX);
		assertThat(parse("cartao.ofx").format()).isEqualTo(Format.OFX);
		assertThat(parse("banco.csv").format()).isEqualTo(Format.CSV);
		assertThat(parse("banco-virgula.csv").format()).isEqualTo(Format.CSV);
		assertThat(parse("resumo.csv").format()).isEqualTo(Format.CSV);
	}

	@Test
	void theSameMovementsComeOutOfAnyOfTheThreeFormats() {
		for (String name : new String[] { "banco.ofx", "banco2.ofx", "banco.csv" }) {
			List<StatementLine> lines = parse(name).lines();

			assertThat(lines).as(name).hasSize(4);
			assertThat(lines).as(name).extracting(StatementLine::amountCents).containsExactly(500000L, -2350L, -18090L, -2350L);
			assertThat(lines).as(name).extracting(StatementLine::occurredOn).containsExactly(LocalDate.of(2026, 11, 5),
					LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 6));
			assertThat(lines).as(name).allSatisfy(line -> {
				assertThat(line.valid()).isTrue();
				assertThat(line.key()).isNotBlank().hasSizeLessThanOrEqualTo(200);
			});
		}
	}

	@Test
	void anUnrecognizedOrEmptyFileIsRefusedAsAWhole() {
		refused("isto não é um extrato\nnem tem colunas\n".getBytes(StandardCharsets.UTF_8), "formato não reconhecido");
		refused("{\"json\": true}".getBytes(StandardCharsets.UTF_8), "formato não reconhecido");
		refused(new byte[0], "vazio");
		refused("   \n\n  ".getBytes(StandardCharsets.UTF_8), "vazio");
		refused("﻿".getBytes(StandardCharsets.UTF_8), "vazio");
		refused("Data;Descrição;Valor\n".getBytes(StandardCharsets.UTF_8), "nenhum movimento");
		refused("<OFX><BANKMSGSRSV1></BANKMSGSRSV1></OFX>".getBytes(StandardCharsets.UTF_8), "nenhum movimento");
		refused(new byte[] { 0, 1, 2, (byte) 0xFF, (byte) 0xFE, 3 }, "formato não reconhecido");
	}

	@Test
	void theErrorMessagesNeverQuoteTheFileContent() {
		String secret = "SEGREDO-DA-DESCRICAO-123";

		assertThatThrownBy(() -> parseText(secret + "\noutra linha " + secret)).isInstanceOf(FieldValidationException.class)
				.satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
		assertThatThrownBy(() -> parseText("Data;Descrição;Valor\n")).satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
	}

	// ---- codificação -------------------------------------------------------------------------------------------

	@Test
	void decodesUtf8WithAndWithoutBomAndWindows1252() {
		String csv = "Data;Descrição;Valor\n05/11/2026;AÇAÍ MÃE JOSÉ;-10,00\n";
		byte[] utf8 = csv.getBytes(StandardCharsets.UTF_8);
		byte[] withBom = ("﻿" + csv).getBytes(StandardCharsets.UTF_8);
		byte[] windows = csv.getBytes(Charset.forName("windows-1252"));
		byte[] latin1 = csv.getBytes(StandardCharsets.ISO_8859_1);

		for (byte[] bytes : new byte[][] { utf8, withBom, windows, latin1 }) {
			assertThat(StatementParser.parse(bytes).lines()).singleElement()
					.satisfies(line -> assertThat(line.description()).isEqualTo("AÇAÍ MÃE JOSÉ"));
		}
	}

	@Test
	void aWindows1252OfxHasItsAccentsRight() {
		String ofx = "OFXHEADER:100\nDATA:OFXSGML\nCHARSET:1252\n\n<OFX><STMTTRN><DTPOSTED>20261106<TRNAMT>-1.00<FITID>a"
				+ "<MEMO>FARMÁCIA SÃO JOÃO</STMTTRN></OFX>";

		assertThat(StatementParser.parse(ofx.getBytes(Charset.forName("windows-1252"))).lines()).singleElement()
				.satisfies(line -> assertThat(line.description()).isEqualTo("FARMÁCIA SÃO JOÃO"));
	}

	// ---- limites -----------------------------------------------------------------------------------------------

	@Test
	void theFileSizeLimitIsTwoMegabytes() {
		StringBuilder big = new StringBuilder("Data;Descrição;Valor\n05/11/2026;");
		big.append("x".repeat(StatementParser.MAX_BYTES));

		refused(big.toString().getBytes(StandardCharsets.UTF_8), "2 MB");
		refused(new byte[StatementParser.MAX_BYTES + 1], "2 MB");
	}

	@Test
	void theMovementLimitIsOneThousand() {
		StringBuilder csv = new StringBuilder("Data;Descrição;Valor\n");
		for (int i = 0; i < 1000; i++) {
			csv.append("05/11/2026;Compra ").append(i).append(";-1,00\n");
		}
		assertThat(parseText(csv.toString()).lines()).hasSize(1000);

		csv.append("05/11/2026;Compra extra;-1,00\n");
		refused(csv.toString().getBytes(StandardCharsets.UTF_8), "1.000");
	}

	// ---- normalização ------------------------------------------------------------------------------------------

	@Test
	void theDescriptionIsCleanedAndCutAtTwoHundredCharacters() {
		String csv = "Data;Descrição;Valor\n05/11/2026;  Padaria    do   Zé  ;-1,00\n05/11/2026;" + "a".repeat(250) + ";-2,00\n"
				+ "05/11/2026;" + "ab ".repeat(100) + ";-3,00\n";

		List<StatementLine> lines = parseText(csv).lines();

		assertThat(lines.get(0).description()).isEqualTo("Padaria do Zé");
		assertThat(lines.get(1).description()).hasSize(200).isEqualTo("a".repeat(200));
		assertThat(lines.get(2).description().length()).isLessThanOrEqualTo(200);
		assertThat(lines.get(2).description()).doesNotStartWith(" ").doesNotEndWith(" ");
	}

	@Test
	void cuttingNeverSplitsASurrogatePair() {
		String emoji = "😀";
		String description = "a".repeat(199) + emoji;
		String csv = "Data;Descrição;Valor\n05/11/2026;" + description + ";-1,00\n";

		String cut = parseText(csv).lines().get(0).description();

		assertThat(cut).isEqualTo("a".repeat(199));
	}

	@Test
	void anEmptyDescriptionAndDatesOutOfRangeAreProblemsWithoutAKey() {
		String csv = "Data;Descrição;Valor\n05/11/2026;;-1,00\n05/11/1999;Antiga;-1,00\n05/11/2101;Futura;-1,00\n"
				+ "01/01/2000;Limite;-1,00\n31/12/2100;Limite;-1,00\n";

		List<StatementLine> lines = parseText(csv).lines();

		assertThat(lines.get(0).problems()).containsExactly("descrição vazia");
		assertThat(lines.get(1).problems()).containsExactly("data fora de 2000-01-01 a 2100-12-31");
		assertThat(lines.get(2).problems()).containsExactly("data fora de 2000-01-01 a 2100-12-31");
		assertThat(lines.subList(0, 3)).extracting(StatementLine::key).containsOnlyNulls();
		assertThat(lines.get(3).valid()).isTrue();
		assertThat(lines.get(4).valid()).isTrue();
	}

	@Test
	void aBadLineDoesNotStopTheGoodOnes() {
		String csv = "Data;Descrição;Valor\n05/11/2026;Boa;-1,00\n05/11/2026;Ruim;abc\n06/11/2026;Outra boa;-2,00\n";

		List<StatementLine> lines = parseText(csv).lines();

		assertThat(lines).extracting(StatementLine::valid).containsExactly(true, false, true);
		assertThat(lines.get(1).amountCents()).isNull();
		assertThat(lines.get(1).key()).isNull();
	}

	// ---- chaves ------------------------------------------------------------------------------------------------

	@Test
	void anIdentifierMakesTheKeyAndARepeatedOneIsAProblem() {
		String ofx = "<OFX><STMTTRN><DTPOSTED>20261106<TRNAMT>-1.00<FITID>X1<MEMO>a</STMTTRN>"
				+ "<STMTTRN><DTPOSTED>20261107<TRNAMT>-2.00<FITID>X1<MEMO>b</STMTTRN>"
				+ "<STMTTRN><DTPOSTED>20261108<TRNAMT>-3.00<FITID>X2<MEMO>c</STMTTRN></OFX>";

		List<StatementLine> lines = parseText(ofx).lines();

		assertThat(lines.get(0).key()).isEqualTo("id:X1");
		assertThat(lines.get(1).problems()).containsExactly("identificador repetido");
		assertThat(lines.get(1).key()).isNull();
		assertThat(lines.get(2).key()).isEqualTo("id:X2");
	}

	@Test
	void withoutAnIdentifierTheKeyIsDerivedAndIdenticalLinesGetDifferentKeys() {
		List<StatementLine> lines = parse("banco.csv").lines();

		assertThat(lines.get(1).key()).startsWith("h:");
		// as duas padarias (mesma data, valor e descrição) têm chaves diferentes
		assertThat(lines.get(1).key()).isNotEqualTo(lines.get(3).key());
		assertThat(lines).extracting(StatementLine::key).doesNotHaveDuplicates();
	}

	@Test
	void theSameFileAlwaysGivesTheSameKeys() {
		assertThat(parse("banco.csv").lines()).extracting(StatementLine::key)
				.containsExactlyElementsOf(parse("banco.csv").lines().stream().map(StatementLine::key).toList());
		assertThat(parse("banco.ofx").lines()).extracting(StatementLine::key).containsExactly("id:1", "id:2", "id:3", "id:4");
	}

	@Test
	void anOverlappingStatementKeepsTheKeysOfTheCommonMovements() {
		String first = "Data;Descrição;Valor\n05/11/2026;Padaria;-1,00\n05/11/2026;Padaria;-1,00\n06/11/2026;Mercado;-5,00\n";
		String second = "Data;Descrição;Valor\n05/11/2026;Padaria;-1,00\n05/11/2026;Padaria;-1,00\n06/11/2026;Mercado;-5,00\n"
				+ "07/11/2026;Farmácia;-9,00\n";

		List<String> a = parseText(first).lines().stream().map(StatementLine::key).toList();
		List<String> b = parseText(second).lines().stream().map(StatementLine::key).toList();

		assertThat(b.subList(0, 3)).containsExactlyElementsOf(a);
		assertThat(b.get(3)).isNotIn(a);
	}

	@Test
	void aLongIdentifierStillMakesAKeyThatFits() {
		String ofx = "<OFX><STMTTRN><DTPOSTED>20261106<TRNAMT>-1.00<FITID>" + "9".repeat(255) + "<MEMO>a</STMTTRN></OFX>";

		assertThat(parseText(ofx).lines().get(0).key()).hasSizeLessThanOrEqualTo(200).startsWith("id:h:");
	}

}
