package com.tostao.tostao_api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.tostao.tostao_api.common.error.FieldValidationException;

/** Regras puras de período (mês e semana ISO): sem Spring, sem banco. */
class PeriodTest {

	private static FieldValidationException rejection(Runnable action) {
		return catchThrowableOfType(FieldValidationException.class, action::run);
	}

	@Test
	void aMonthGoesFromTheFirstToTheLastDay() {
		Period november = Period.ofMonth("2026-11");

		assertThat(november.type()).isEqualTo(PeriodType.MONTH);
		assertThat(november.id()).isEqualTo("2026-11");
		assertThat(november.start()).isEqualTo(LocalDate.of(2026, 11, 1));
		assertThat(november.end()).isEqualTo(LocalDate.of(2026, 11, 30));
		assertThat(november.days()).hasSize(30).first().isEqualTo(LocalDate.of(2026, 11, 1));
		assertThat(Period.ofMonth("2024-02").days()).hasSize(29);
		assertThat(Period.ofMonth("2025-02").days()).hasSize(28);
	}

	@Test
	void theFirstAndLastAcceptedMonthsWork() {
		assertThat(Period.ofMonth("2000-01").start()).isEqualTo(LocalDate.of(2000, 1, 1));
		assertThat(Period.ofMonth("2100-12").end()).isEqualTo(LocalDate.of(2100, 12, 31));
	}

	@ParameterizedTest
	@ValueSource(strings = { "1999-12", "2101-01", "2026-13", "2026-00", "2026-1", "novembro", "2026-11-05", "",
			" 2026-11", "2026-11 ", "2026-11\n", "٢٠٢٦-١١", "2026/11", "+2026-11" })
	void invalidMonthsAreRejectedOnTheMonthField(String text) {
		FieldValidationException error = rejection(() -> Period.ofMonth(text));

		assertThat(error).isNotNull();
		assertThat(error.field()).isEqualTo("month");
	}

	@Test
	void aNullMonthIsRejectedToo() {
		assertThat(rejection(() -> Period.ofMonth(null)).field()).isEqualTo("month");
	}

	@Test
	void aWeekGoesFromMondayToSunday() {
		Period week = Period.ofWeek("2026-W45");

		assertThat(week.type()).isEqualTo(PeriodType.WEEK);
		assertThat(week.id()).isEqualTo("2026-W45");
		assertThat(week.start()).isEqualTo(LocalDate.of(2026, 11, 2));
		assertThat(week.end()).isEqualTo(LocalDate.of(2026, 11, 8));
		assertThat(week.days()).hasSize(7);
		assertThat(week.start().getDayOfWeek().getValue()).isEqualTo(1);
		assertThat(week.end().getDayOfWeek().getValue()).isEqualTo(7);
	}

	@Test
	void week53ExistsOnlyInYearsWithFiftyThreeWeeks() {
		Period week53 = Period.ofWeek("2026-W53");

		assertThat(week53.start()).isEqualTo(LocalDate.of(2026, 12, 28));
		assertThat(week53.end()).isEqualTo(LocalDate.of(2027, 1, 3));
		assertThat(rejection(() -> Period.ofWeek("2025-W53")).field()).isEqualTo("week");
	}

	@Test
	void weeksCanCrossTheYearBoundary() {
		Period first = Period.ofWeek("2025-W01");

		assertThat(first.start()).isEqualTo(LocalDate.of(2024, 12, 30));
		assertThat(first.end()).isEqualTo(LocalDate.of(2025, 1, 5));
	}

	@Test
	void theFirstAndLastAcceptedWeeksWork() {
		assertThat(Period.ofWeek("2000-W01").start()).isEqualTo(LocalDate.of(2000, 1, 3));
		assertThat(Period.ofWeek("2100-W52").id()).isEqualTo("2100-W52");
		assertThat(rejection(() -> Period.ofWeek("2100-W53")).field()).isEqualTo("week");
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-W00", "2026-W54", "2026W45", "1999-W52", "2101-W01", "", "2026-w45", "2026-W5",
			"2026-W45 ", " 2026-W45", "2026-W45\n", "2026-45", "semana" })
	void invalidWeeksAreRejectedOnTheWeekField(String text) {
		FieldValidationException error = rejection(() -> Period.ofWeek(text));

		assertThat(error).isNotNull();
		assertThat(error.field()).isEqualTo("week");
	}

	@Test
	void aNullWeekIsRejectedToo() {
		assertThat(rejection(() -> Period.ofWeek(null)).field()).isEqualTo("week");
	}

	@Test
	void thePreviousPeriodOfAMonthIsTheMonthBefore() {
		assertThat(Period.ofMonth("2026-11").previous().id()).isEqualTo("2026-10");
		assertThat(Period.ofMonth("2026-01").previous().id()).isEqualTo("2025-12");
		assertThat(Period.ofMonth("2026-03").previous().days()).hasSize(28);
		assertThat(Period.ofMonth("2000-01").previous().id()).isEqualTo("1999-12");
	}

	@Test
	void thePreviousPeriodOfAWeekIsSevenDaysBefore() {
		Period previous = Period.ofWeek("2026-W45").previous();

		assertThat(previous.id()).isEqualTo("2026-W44");
		assertThat(previous.start()).isEqualTo(LocalDate.of(2026, 10, 26));
		assertThat(previous.end()).isEqualTo(LocalDate.of(2026, 11, 1));
		assertThat(Period.ofWeek("2026-W01").previous().id()).isEqualTo("2025-W52");
		assertThat(Period.ofWeek("2027-W01").previous().id()).isEqualTo("2026-W53");
		assertThat(Period.ofWeek("2000-W01").previous().id()).isEqualTo("1999-W52");
	}

	@Test
	void askingForMonthAndWeekTogetherIsRejectedNamingBoth() {
		FieldValidationException error = rejection(() -> Period.resolve("2026-11", "2026-W45", Instant.now()));

		assertThat(error.field()).isEqualTo("week");
		assertThat(error.getMessage()).contains("month").contains("week");
	}

	@Test
	void anEmptyValueIsInvalidNotAbsent() {
		assertThat(rejection(() -> Period.resolve("", null, Instant.now())).field()).isEqualTo("month");
		assertThat(rejection(() -> Period.resolve(null, "", Instant.now())).field()).isEqualTo("week");
	}

	@Test
	void withoutAnyValueTheCurrentMonthInBrasiliaIsUsed() {
		assertThat(currentId("2026-11-30T23:30:00-03:00")).isEqualTo("2026-11");
		// 01:00 UTC de 1º/12 ainda é 22h de 30/11 em Brasília
		assertThat(currentId("2026-12-01T01:00:00Z")).isEqualTo("2026-11");
		assertThat(currentId("2026-12-01T03:00:00Z")).isEqualTo("2026-12");
		assertThat(currentId("2026-01-01T02:59:59Z")).isEqualTo("2025-12");
		assertThat(currentId("2026-01-01T03:00:00Z")).isEqualTo("2026-01");
	}

	@Test
	void resolvePicksTheRequestedKind() {
		Instant now = Instant.parse("2026-11-06T12:00:00Z");

		assertThat(Period.resolve("2026-10", null, now).id()).isEqualTo("2026-10");
		assertThat(Period.resolve(null, "2026-W45", now).id()).isEqualTo("2026-W45");
		assertThat(Period.resolve(null, null, now).type()).isEqualTo(PeriodType.MONTH);
	}

	@Test
	void withoutAWeekTheCurrentIsoWeekInBrasiliaIsUsed() {
		assertThat(currentWeekId("2026-11-05T12:00:00Z")).isEqualTo("2026-W45");
		assertThat(currentWeekId("2026-11-08T23:30:00-03:00")).isEqualTo("2026-W45");
		// 02:00 UTC de segunda ainda é 23h de domingo em Brasília; 03:00 UTC já é segunda
		assertThat(currentWeekId("2026-11-09T02:00:00Z")).isEqualTo("2026-W45");
		assertThat(currentWeekId("2026-11-09T03:00:00Z")).isEqualTo("2026-W46");
		assertThat(currentWeekId("2027-01-03T12:00:00Z")).isEqualTo("2026-W53");
		assertThat(currentWeekId("2027-01-04T12:00:00Z")).isEqualTo("2027-W01");
		assertThat(Period.resolveWeek(null, Instant.parse("2026-11-05T12:00:00Z")).start())
				.isEqualTo(LocalDate.of(2026, 11, 2));
	}

	@Test
	void resolveWeekUsesTheRequestedWeekAndRejectsBadText() {
		Instant now = Instant.parse("2026-11-06T12:00:00Z");

		assertThat(Period.resolveWeek("2026-W45", now).id()).isEqualTo("2026-W45");
		assertThat(Period.resolveWeek("2026-W53", now).start()).isEqualTo(LocalDate.of(2026, 12, 28));
		assertThat(rejection(() -> Period.resolveWeek("", now)).field()).isEqualTo("week");
		assertThat(rejection(() -> Period.resolveWeek("2026-W54", now)).field()).isEqualTo("week");
		assertThat(rejection(() -> Period.resolveWeek("2026-W5", now)).field()).isEqualTo("week");
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-1", "202611", "2026-13", "2026-00", "1999-12", "2101-01", "2026-11-01", "" })
	void aBadMonthIsRejectedNamingTheGivenField(String text) {
		assertThat(rejection(() -> Period.ofMonth(text, "fromMonth")).field()).isEqualTo("fromMonth");
		assertThat(rejection(() -> Period.ofMonth(null, "fromMonth")).field()).isEqualTo("fromMonth");
		// a forma sem campo continua apontando "month"
		assertThat(rejection(() -> Period.ofMonth(text)).field()).isEqualTo("month");
	}

	@Test
	void theFieldOverloadAcceptsTheSameMonthsAsTheOriginal() {
		Period november = Period.ofMonth("2026-11", "fromMonth");

		assertThat(november.id()).isEqualTo("2026-11");
		assertThat(november.start()).isEqualTo(LocalDate.of(2026, 11, 1));
		assertThat(november.end()).isEqualTo(LocalDate.of(2026, 11, 30));
		assertThat(Period.ofMonth("2028-02", "x").end()).isEqualTo(LocalDate.of(2028, 2, 29));
		assertThat(Period.ofMonth("2027-02", "x").end()).isEqualTo(LocalDate.of(2027, 2, 28));
	}

	@Test
	void theLastAcceptedMonthStartsOnTheFirstOfDecember2100() {
		Period last = Period.ofMonth("2100-12");

		assertThat(Period.LAST_MONTH_START).isEqualTo(LocalDate.of(2100, 12, 1)).isEqualTo(last.start());
		assertThat(rejection(() -> Period.ofMonth("2101-01")).field()).isEqualTo("month");
	}

	@Test
	void theCurrentMonthFollowsBrasiliaAtTheMonthBoundary() {
		assertThat(currentId("2026-11-15T12:00:00Z")).isEqualTo("2026-11");
		// 02:59:59 UTC de 1º/11 ainda é 23:59:59 de 31/10 em Brasília
		assertThat(currentId("2026-11-01T02:59:59Z")).isEqualTo("2026-10");
		assertThat(currentId("2026-11-01T03:00:00Z")).isEqualTo("2026-11");
		assertThat(currentId("2027-01-01T02:00:00Z")).isEqualTo("2026-12");
	}

	@Test
	void todayIsTheDateInBrasilia() {
		// 02:59:59 UTC ainda é 23:59:59 do dia anterior em Brasília; 03:00 UTC já é o dia seguinte
		assertThat(Period.today(Instant.parse("2024-11-20T02:59:59Z"))).isEqualTo(LocalDate.of(2024, 11, 19));
		assertThat(Period.today(Instant.parse("2024-11-20T03:00:00Z"))).isEqualTo(LocalDate.of(2024, 11, 20));
		assertThat(Period.today(Instant.parse("2025-01-01T02:00:00Z"))).isEqualTo(LocalDate.of(2024, 12, 31));
	}

	private static String currentWeekId(String instant) {
		return Period.resolveWeek(null, OffsetDateTime.parse(instant).toInstant()).id();
	}

	private static String currentId(String instant) {
		return Period.resolve(null, null, OffsetDateTime.parse(instant).toInstant()).id();
	}

}
