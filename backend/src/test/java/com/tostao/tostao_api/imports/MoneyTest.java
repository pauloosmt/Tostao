package com.tostao.tostao_api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Valores de extrato para centavos exatos (FR-004): nunca ponto flutuante, nunca arredondando em silêncio. */
class MoneyTest {

	private static void csv(String text, long cents) {
		Money.Result result = Money.csv(text);
		assertThat(result.problem()).as("csv <%s>", text).isNull();
		assertThat(result.cents()).as("csv <%s>", text).isEqualTo(cents);
	}

	private static void ofx(String text, long cents) {
		Money.Result result = Money.ofx(text);
		assertThat(result.problem()).as("ofx <%s>", text).isNull();
		assertThat(result.cents()).as("ofx <%s>", text).isEqualTo(cents);
	}

	private static void csvProblem(String text, String problem) {
		Money.Result result = Money.csv(text);
		assertThat(result.cents()).as("csv <%s>", text).isNull();
		assertThat(result.problem()).as("csv <%s>", text).isEqualTo(problem);
	}

	private static void ofxProblem(String text, String problem) {
		Money.Result result = Money.ofx(text);
		assertThat(result.cents()).as("ofx <%s>", text).isNull();
		assertThat(result.problem()).as("ofx <%s>", text).isEqualTo(problem);
	}

	@Test
	void csvReadsBrazilianAndInternationalNotation() {
		csv("1.234,56", 123456);
		csv("-1234.56", -123456);
		csv("R$ 12,50", 1250);
		csv("-R$ 12,50", -1250);
		csv("R$ -12,50", -1250);
		csv("+99,90", 9990);
		csv("1,234.56", 123456);
		csv("1.234.567,89", 123456789);
		csv("0,01", 1);
		csv(",50", 50);
		csv("12", 1200);
		csv("12,5", 1250);
		csv("12.5", 1250);
		csv("  7,00  ", 700);
		csv("5.000,00", 500000);
		csv("5000.00", 500000);
	}

	@Test
	void csvTreatsASingleSeparatorFollowedByThreeDigitsAsThousands() {
		csv("1.234", 123400);
		csv("12,345", 1234500);
		csv("10.505", 1050500);
		csv("1.234.567", 123456700);
		csv("-2.500", -250000);
	}

	@Test
	void csvRefusesWrongGroupsAndMoreThanTwoDecimals() {
		csvProblem("10,5050", "valor com mais de duas casas decimais");
		csvProblem("1.234,567", "valor com mais de duas casas decimais");
		csvProblem("0,500", "valor ilegível");
		csvProblem("1234,567", "valor ilegível");
		csvProblem("1.23.456", "valor ilegível");
		csvProblem("12,", "valor ilegível");
		csvProblem("1,2,3,4", "valor ilegível");
		csvProblem("1.234,56,7", "valor ilegível");
		csvProblem("1,234,56", "valor ilegível");
	}

	@Test
	void ofxHasNoThousandsSeparator() {
		ofx("5000.00", 500000);
		ofx("-23.50", -2350);
		ofx("-180,90", -18090);
		ofx("+100", 10000);
		ofx("0.05", 5);
		ofx("1500", 150000);
		ofxProblem("10.505", "valor com mais de duas casas decimais");
		ofxProblem("1.234,56", "valor ilegível");
		ofxProblem("1,234.56", "valor ilegível");
		ofxProblem("12.", "valor ilegível");
	}

	@Test
	void zeroAndTheLimitsAreProblems() {
		csvProblem("0,00", "valor zero");
		csvProblem("-0", "valor zero");
		ofxProblem("0.00", "valor zero");
		csv("10.000.000.000,00", 1_000_000_000_000L);
		csv("-10.000.000.000,00", -1_000_000_000_000L);
		csvProblem("10.000.000.000,01", "valor acima do limite");
		csvProblem("99.999.999.999.999,00", "valor acima do limite");
		ofx("10000000000.00", 1_000_000_000_000L);
		ofxProblem("10000000000.01", "valor acima do limite");
		ofxProblem("99999999999999999999.99", "valor acima do limite");
	}

	@Test
	void emptyAndGarbageAreProblems() {
		csvProblem(null, "valor ausente");
		csvProblem("", "valor ausente");
		csvProblem("   ", "valor ausente");
		csvProblem("R$", "valor ausente");
		csvProblem("abc", "valor ilegível");
		csvProblem("12abc", "valor ilegível");
		csvProblem("--5", "valor ilegível");
		csvProblem("5-", "valor ilegível");
		csvProblem("(5,00)", "valor ilegível");
		csvProblem("1e3", "valor ilegível");
		csvProblem("-", "valor ilegível");
		csvProblem(".", "valor ilegível");
		ofxProblem(null, "valor ausente");
		ofxProblem("NaN", "valor ilegível");
	}

	@Test
	void theResultIsExactForValuesThatBreakFloatingPoint() {
		// 0,1 + 0,2 e 4,35 * 100 dão resíduos em ponto flutuante; aqui são centavos exatos
		csv("4,35", 435);
		csv("0,29", 29);
		csv("1.005,05", 100505);
		ofx("4.35", 435);
		ofx("19.99", 1999);
	}

}
