package com.tostao.tostao_api.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.imports.StatementParser.Format;

/** O leitor de PDF de texto: movimentos pelo padrão da linha, e recusa do que não é lido com segurança (FR-003a, FR-022). */
class PdfReaderTest {

	private static List<RawLine> read(String... lines) {
		return PdfReader.read(Pdfs.of(lines));
	}

	private static void refused(byte[] pdf, String reason) {
		assertThatThrownBy(() -> PdfReader.read(pdf)).isInstanceOf(FieldValidationException.class).hasMessageContaining(reason)
				.satisfies(e -> assertThat(((FieldValidationException) e).field()).isEqualTo("file"));
	}

	// ---- o padrão da linha -------------------------------------------------------------------------------------

	@Test
	void readsTheMovementsOfAStatementAndIgnoresEverythingElse() {
		List<RawLine> lines = read("Extrato de conta corrente", "Periodo: 01/11/2026 a 10/11/2026", "Data Descricao Valor",
				"05/11/2026 SALARIO EMPRESA XYZ 5.000,00 C", "06/11/2026 PADARIA DO ZE 23,50 D", "07/11/2026 MERCADO CENTRAL -180,90",
				"06/11/2026 PADARIA DO ZE 23,50-", "SALDO FINAL 4.772,10", "08/11/2026 SALDO DO DIA 4.772,10", "Pagina 1 de 1");

		assertThat(lines).hasSize(4);
		assertThat(lines).extracting(RawLine::cents).containsExactly(500000L, -2350L, -18090L, -2350L);
		assertThat(lines).extracting(RawLine::date).containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6),
				LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 6));
		assertThat(lines).extracting(RawLine::description).containsExactly("SALARIO EMPRESA XYZ", "PADARIA DO ZE",
				"MERCADO CENTRAL", "PADARIA DO ZE");
		assertThat(lines).extracting(RawLine::identifier).containsOnlyNulls();
		assertThat(lines).allSatisfy(line -> assertThat(line.problems()).isEmpty());
	}

	@Test
	void theDateMayBeFullShortOrWithoutYear() {
		List<RawLine> lines = read("Periodo 01/11/2026 a 30/11/2026", "05/11/2026 Completa 1,00 D", "06/11/26 Curta 2,00 D",
				"07/11 Sem ano 3,00 D");

		assertThat(lines).extracting(RawLine::date).containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6),
				LocalDate.of(2026, 11, 7));
	}

	@Test
	void withoutAnyFullDateAMovementWithoutYearIsAProblemLine() {
		List<RawLine> lines = read("Meus movimentos", "07/11 Sem ano 3,00 D", "06/11/26 Curta 2,00 D");

		assertThat(lines).hasSize(2);
		assertThat(lines.get(0).problems()).containsExactly("ano não identificado");
		assertThat(lines.get(0).date()).isNull();
		assertThat(lines.get(1).problems()).isEmpty();
	}

	@Test
	void theSignComesFromTheLeadingMinusTheTrailingMinusOrTheLetter() {
		List<RawLine> lines = read("05/11/2026 Menos antes -10,00", "05/11/2026 Menos depois 11,00-", "05/11/2026 Debito 12,00 D",
				"05/11/2026 Debito minusculo 13,00 d", "05/11/2026 Credito 14,00 C", "05/11/2026 Sem sinal 15,00",
				"05/11/2026 Mais explicito +16,00", "05/11/2026 Menos e letra -17,00 D", "05/11/2026 Prefixo R$ 18,00 D",
				"05/11/2026 Prefixo e sinal R$ -19,00");

		assertThat(lines).extracting(RawLine::cents).containsExactly(-1000L, -1100L, -1200L, -1300L, 1400L, 1500L, 1600L, -1700L,
				-1800L, -1900L);
	}

	@Test
	void brazilianAndInternationalThousandsAndDecimals() {
		List<RawLine> lines = read("05/11/2026 Milhar br 1.234,56 D", "05/11/2026 Milhar us 1,234.56 D", "05/11/2026 Grande 12.345.678,90 C",
				"05/11/2026 Ponto decimal 99.90 D", "05/11/2026 Virgula 0,05 D");

		assertThat(lines).extracting(RawLine::cents).containsExactly(-123456L, -123456L, 1234567890L, -9990L, -5L);
	}

	@Test
	void linesThatDoNotFollowThePatternAreIgnored() {
		List<RawLine> lines = read("AGENCIA 0001 CONTA 12345-6", "05/11/2026 AGENCIA 0001 CONTA 12345", "05/11/2026 Sem valor",
				"Texto solto 12,50", "05/11/2026 Valor com uma casa 12,5", "05/11/2026", "12,50", "", "05/11/2026 Boa 7,00 D");

		assertThat(lines).singleElement().satisfies(line -> assertThat(line.description()).isEqualTo("Boa"));
	}

	@Test
	void anImpossibleDateIsAProblemLineNotASkippedOne() {
		List<RawLine> lines = read("05/11/2026 Boa 1,00 D", "31/02/2026 Fevereiro 2,00 D", "32/11/2026 Dia 32 3,00 D");

		assertThat(lines).hasSize(3);
		assertThat(lines.get(1).problems()).containsExactly("data inválida");
		assertThat(lines.get(2).problems()).containsExactly("data inválida");
	}

	@Test
	void theDescriptionKeepsDatesAndNumbersThatAreNotTheAmount() {
		List<RawLine> lines = read("05/11/2026 LOJA ELETRO 02/10 1.250,00 D", "06/11/2026 PIX 12345 enviado 50,00 D",
				"07/11/2026 FARMÁCIA SÃO JOÃO 33,40 D");

		assertThat(lines).extracting(RawLine::description).containsExactly("LOJA ELETRO 02/10", "PIX 12345 enviado",
				"FARMÁCIA SÃO JOÃO");
		assertThat(lines).extracting(RawLine::cents).containsExactly(-125000L, -5000L, -3340L);
	}

	@Test
	void balanceLinesAreIgnoredWithAnyCaseOrAccent() {
		List<RawLine> lines = read("05/11/2026 SALDO ANTERIOR 100,00 C", "05/11/2026 Saldo do dia 90,00 C", "05/11/2026 Saldo final 80,00 C",
				"05/11/2026 Compra 1,00 D");

		assertThat(lines).singleElement().satisfies(line -> assertThat(line.description()).isEqualTo("Compra"));
	}

	@Test
	void readsMovementsOnSeveralPages() {
		String[] rows = new String[120];
		for (int i = 0; i < rows.length; i++) {
			rows[i] = "05/11/2026 Compra %03d %d,00 D".formatted(i, (i % 9) + 1);
		}

		List<RawLine> lines = read(rows);

		assertThat(lines).hasSize(120);
		assertThat(lines.get(119).description()).isEqualTo("Compra 119");
	}

	@Test
	void repeatedSpacesAreCollapsed() {
		assertThat(read("05/11/2026     PADARIA     DO    ZE      23,50     D")).singleElement()
				.satisfies(line -> assertThat(line.description()).isEqualTo("PADARIA DO ZE"));
	}

	// ---- recusas -----------------------------------------------------------------------------------------------

	@Test
	void anImageOnlyPdfIsRefused() {
		refused(Pdfs.imageOnly(), "não tem texto selecionável");
	}

	@Test
	void aPasswordProtectedPdfIsRefused() {
		refused(Pdfs.encrypted("05/11/2026 Compra 1,00 D"), "protegido por senha");
	}

	@Test
	void moreThanTwentyPagesAreRefused() {
		assertThat(PdfReader.read(Pdfs.withPages(PdfReader.MAX_PAGES))).hasSize(PdfReader.MAX_PAGES);
		refused(Pdfs.withPages(PdfReader.MAX_PAGES + 1), "mais de 20 páginas");
	}

	@Test
	void aTruncatedOrCorruptedPdfIsRefusedWithoutBreaking() {
		byte[] pdf = Pdfs.of("05/11/2026 Compra 1,00 D");

		refused(Arrays.copyOf(pdf, pdf.length / 2), "não foi possível ler o PDF");
		refused("%PDF-1.4\n".concat("lixo ".repeat(200)).getBytes(StandardCharsets.ISO_8859_1), "não foi possível ler o PDF");
		refused("%PDF-".getBytes(StandardCharsets.ISO_8859_1), "não foi possível ler o PDF");
		byte[] garbage = new byte[2000];
		new java.util.Random(7).nextBytes(garbage);
		System.arraycopy("%PDF-1.7\n".getBytes(StandardCharsets.ISO_8859_1), 0, garbage, 0, 9);
		refused(garbage, "não foi possível ler o PDF");
	}

	@Test
	void aReadThatTakesTooLongIsCutOff() {
		long start = System.nanoTime();

		assertThatThrownBy(() -> PdfReader.read(Pdfs.of("05/11/2026 Compra 1,00 D"), Duration.ofMillis(200), bytes -> {
			try {
				Thread.sleep(10_000);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return "";
		})).isInstanceOf(FieldValidationException.class).hasMessageContaining("demorou demais");

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
	}

	@Test
	void theErrorMessagesNeverQuoteTheDocumentContent() {
		String secret = "SEGREDO-NO-PDF-999";

		assertThatThrownBy(() -> PdfReader.read(Pdfs.encrypted("05/11/2026 " + secret + " 1,00 D")))
				.satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
		assertThatThrownBy(() -> PdfReader.read(("%PDF-1.4 " + secret).getBytes(StandardCharsets.ISO_8859_1)))
				.satisfies(e -> assertThat(e.getMessage()).doesNotContain(secret));
	}

	// ---- documento hostil --------------------------------------------------------------------------------------

	@Test
	void aScriptAnOpenActionAndAnExternalLinkAreIgnored() {
		List<RawLine> lines = PdfReader.read(Pdfs.withActions("05/11/2026 Compra 1,00 D", "06/11/2026 Outra 2,00 D"));

		assertThat(lines).hasSize(2);
		assertThat(lines).extracting(RawLine::cents).containsExactly(-100L, -200L);
	}

	// ---- reconhecimento ----------------------------------------------------------------------------------------

	@Test
	void thePdfSignatureIsRecognizedByTheContentNotByTheName() {
		assertThat(PdfReader.looksLikePdf(Pdfs.of("x"))).isTrue();
		assertThat(PdfReader.looksLikePdf("lixo antes\n%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1))).isTrue();
		assertThat(PdfReader.looksLikePdf("Data;Descrição;Valor".getBytes(StandardCharsets.UTF_8))).isFalse();
		assertThat(PdfReader.looksLikePdf(Statements.bytes("banco.ofx"))).isFalse();
		assertThat(PdfReader.looksLikePdf(new byte[0])).isFalse();
		assertThat(PdfReader.looksLikePdf("%PDF".getBytes(StandardCharsets.ISO_8859_1))).isFalse();
		assertThat(PdfReader.looksLikePdf((" ".repeat(2000) + "%PDF-1.4").getBytes(StandardCharsets.ISO_8859_1))).isFalse();
	}

	@Test
	void theParserTreatsItAsAPdfWithDerivedKeysAndDistinctKeysForIdenticalLines() {
		StatementParser.Parsed parsed = StatementParser.parse(Pdfs.of("Periodo 01/11/2026 a 10/11/2026",
				"05/11/2026 SALARIO 5.000,00 C", "06/11/2026 PADARIA DO ZE 23,50 D", "06/11/2026 PADARIA DO ZE 23,50 D"));

		assertThat(parsed.format()).isEqualTo(Format.PDF);
		assertThat(parsed.lines()).hasSize(3);
		assertThat(parsed.lines()).allSatisfy(line -> {
			assertThat(line.valid()).isTrue();
			assertThat(line.key()).matches("h:[0-9a-f]{64}");
		});
		assertThat(parsed.lines()).extracting(StatementLine::key).doesNotHaveDuplicates();
		assertThat(StatementParser.parse(Pdfs.of("Periodo 01/11/2026 a 10/11/2026", "05/11/2026 SALARIO 5.000,00 C",
				"06/11/2026 PADARIA DO ZE 23,50 D", "06/11/2026 PADARIA DO ZE 23,50 D")).lines())
				.extracting(StatementLine::key).containsExactlyElementsOf(parsed.lines().stream().map(StatementLine::key).toList());
	}

	@Test
	void aPdfWithoutAnyMovementIsRefusedAsAWhole() {
		assertThatThrownBy(() -> StatementParser.parse(Pdfs.of("Extrato sem movimentos", "SALDO FINAL 10,00")))
				.isInstanceOf(FieldValidationException.class).hasMessageContaining("nenhum movimento");
	}

}
