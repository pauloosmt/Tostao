package com.tostao.tostao_api.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.tostao.tostao_api.invoice.InvoiceCalendar.Dates;

/** Calendário da fatura sem Spring nem banco: datas, partição dos dias, situação e fatura aberta. */
class InvoiceCalendarTest {

	private static Dates dates(int closingDay, int dueDay, String month) {
		return InvoiceCalendar.dates(closingDay, dueDay, YearMonth.parse(month));
	}

	private static void assertDates(Dates actual, String periodStart, String closing, String due, String nextClosing) {
		assertThat(actual.periodStart()).as("início do período").isEqualTo(LocalDate.parse(periodStart));
		assertThat(actual.closing()).as("fechamento").isEqualTo(LocalDate.parse(closing));
		assertThat(actual.due()).as("vencimento").isEqualTo(LocalDate.parse(due));
		assertThat(actual.nextClosing()).as("fechamento da seguinte").isEqualTo(LocalDate.parse(nextClosing));
	}

	@Test
	void closingBeforeDueFallsInTheSameMonth() {
		// fecha dia 20 e vence dia 28: os dois no mês pedido
		assertDates(dates(20, 28, "2024-11"), "2024-10-21", "2024-11-20", "2024-11-28", "2024-12-20");
		assertThat(dates(20, 28, "2024-11").month()).isEqualTo(YearMonth.of(2024, 11));
	}

	@Test
	void dueBeforeClosingFallsInTheMonthAfterTheClosing() {
		// fecha dia 25 e vence dia 5: o fechamento é do mês anterior
		assertDates(dates(25, 5, "2024-12"), "2024-10-26", "2024-11-25", "2024-12-05", "2024-12-25");
	}

	@Test
	void dueEqualToClosingFallsInTheMonthAfterTheClosing() {
		assertDates(dates(10, 10, "2024-06"), "2024-04-11", "2024-05-10", "2024-06-10", "2024-06-10");
	}

	@Test
	void theYearRollsOverWithoutSkippingAMonth() {
		assertDates(dates(20, 28, "2024-01"), "2023-12-21", "2024-01-20", "2024-01-28", "2024-02-20");
		assertDates(dates(25, 5, "2024-01"), "2023-11-26", "2023-12-25", "2024-01-05", "2024-01-25");
		assertDates(dates(31, 1, "2024-01"), "2023-12-01", "2023-12-31", "2024-01-01", "2024-01-31");
	}

	@Test
	void day31InAShortMonthMeansTheLastDayOfThatMonth() {
		// fecha e vence dia 31: o vencimento igual ao fechamento cai no mês seguinte
		assertDates(dates(31, 31, "2027-02"), "2027-01-01", "2027-01-31", "2027-02-28", "2027-02-28");
		assertDates(dates(31, 31, "2028-02"), "2028-01-01", "2028-01-31", "2028-02-29", "2028-02-29");
		assertDates(dates(31, 31, "2026-04"), "2026-03-01", "2026-03-31", "2026-04-30", "2026-04-30");
		// fecha dia 31 e vence dia 5: o fechamento de fevereiro é o último dia dele
		assertDates(dates(31, 5, "2027-03"), "2027-02-01", "2027-02-28", "2027-03-05", "2027-03-31");
		assertDates(dates(31, 5, "2028-03"), "2028-02-01", "2028-02-29", "2028-03-05", "2028-03-31");
		assertDates(dates(31, 5, "2026-05"), "2026-04-01", "2026-04-30", "2026-05-05", "2026-05-31");
	}

	@Test
	void closingAndDueCanLandOnTheSameDayWhenBothAreAdjusted() {
		// 29 e 30 em fevereiro de 2027 viram 28 os dois, mas a regra "mesmo mês" usa os dias cadastrados
		Dates february = dates(29, 30, "2027-02");
		assertDates(february, "2027-01-30", "2027-02-28", "2027-02-28", "2027-03-29");
		assertThat(february.closing()).isEqualTo(february.due());
		assertDates(dates(29, 30, "2027-03"), "2027-03-01", "2027-03-29", "2027-03-30", "2027-04-29");
	}

	@Test
	void day1ClosingAndDay31Due() {
		assertDates(dates(1, 31, "2027-02"), "2027-01-02", "2027-02-01", "2027-02-28", "2027-03-01");
	}

	@Test
	void everyDayBelongsToExactlyOneInvoiceForEveryCombinationOfDays() {
		YearMonth first = YearMonth.of(2026, 1);
		for (int closing = 1; closing <= 31; closing++) {
			for (int due = 1; due <= 31; due++) {
				List<Dates> invoices = new ArrayList<>();
				for (int i = 0; i < 48; i++) {
					invoices.add(InvoiceCalendar.dates(closing, due, first.plusMonths(i)));
				}
				for (int i = 0; i < invoices.size(); i++) {
					Dates current = invoices.get(i);
					String label = closing + "/" + due + " " + current.month();
					assertThat(current.periodStart()).as(label).isBeforeOrEqualTo(current.closing());
					if (i > 0) {
						// o período começa no dia seguinte ao fechamento da anterior: sem buraco nem sobreposição
						assertThat(current.periodStart()).as(label)
								.isEqualTo(invoices.get(i - 1).closing().plusDays(1));
						assertThat(invoices.get(i - 1).nextClosing()).as(label).isEqualTo(current.closing());
					}
				}
			}
		}
	}

	@Test
	void everyDayInThreeYearsHasExactlyOneInvoiceForTheMainCombinations() {
		int[][] combinations = { { 20, 28 }, { 25, 5 }, { 10, 10 }, { 31, 31 }, { 29, 30 }, { 31, 5 }, { 1, 31 } };
		for (int[] combination : combinations) {
			List<Dates> invoices = new ArrayList<>();
			for (int i = -2; i < 40; i++) {
				invoices.add(InvoiceCalendar.dates(combination[0], combination[1], YearMonth.of(2025, 1).plusMonths(i)));
			}
			for (LocalDate day = LocalDate.of(2025, 1, 1); day.isBefore(LocalDate.of(2028, 1, 1)); day = day.plusDays(1)) {
				LocalDate today = day;
				long containing = invoices.stream()
						.filter(d -> !today.isBefore(d.periodStart()) && !today.isAfter(d.closing())).count();
				assertThat(containing).as(combination[0] + "/" + combination[1] + " em " + day).isEqualTo(1);
			}
		}
	}

	@Test
	void statusIsOpenUpToTheClosingDayAndNeverPaidWhileOpen() {
		LocalDate closing = LocalDate.of(2024, 11, 20);
		LocalDate due = LocalDate.of(2024, 11, 28);

		assertThat(InvoiceCalendar.status(LocalDate.of(2024, 11, 1), closing, due, 100)).isEqualTo(InvoiceStatus.OPEN);
		assertThat(InvoiceCalendar.status(closing, closing, due, 100)).isEqualTo(InvoiceStatus.OPEN);
		assertThat(InvoiceCalendar.status(closing, closing, due, 0)).isEqualTo(InvoiceStatus.OPEN);
	}

	@Test
	void statusAfterTheClosingDependsOnTheDueDateAndWhatIsLeft() {
		LocalDate closing = LocalDate.of(2024, 11, 20);
		LocalDate due = LocalDate.of(2024, 11, 28);

		assertThat(InvoiceCalendar.status(closing.plusDays(1), closing, due, 100)).isEqualTo(InvoiceStatus.CLOSED);
		assertThat(InvoiceCalendar.status(due, closing, due, 100)).isEqualTo(InvoiceStatus.CLOSED);
		assertThat(InvoiceCalendar.status(due.plusDays(1), closing, due, 100)).isEqualTo(InvoiceStatus.OVERDUE);
		assertThat(InvoiceCalendar.status(closing.plusDays(1), closing, due, 0)).isEqualTo(InvoiceStatus.PAID);
		assertThat(InvoiceCalendar.status(due.plusDays(30), closing, due, 0)).isEqualTo(InvoiceStatus.PAID);
	}

	@Test
	void theOpenInvoiceAlwaysContainsTodayAndIsTheOnlyOne() {
		int[][] combinations = { { 20, 28 }, { 25, 5 }, { 10, 10 }, { 31, 31 }, { 29, 30 }, { 31, 5 }, { 1, 31 }, { 5, 15 } };
		for (int[] combination : combinations) {
			for (LocalDate day = LocalDate.of(2025, 1, 1); day.isBefore(LocalDate.of(2028, 1, 1)); day = day.plusDays(1)) {
				YearMonth open = InvoiceCalendar.openDueMonth(combination[0], combination[1], day);
				Dates dates = InvoiceCalendar.dates(combination[0], combination[1], open);
				String label = combination[0] + "/" + combination[1] + " em " + day;

				assertThat(day).as(label).isBetween(dates.periodStart(), dates.closing());
				// e nenhum outro mês de vencimento por perto também a contém
				for (int offset = -4; offset <= 4; offset++) {
					YearMonth other = open.plusMonths(offset);
					if (!other.equals(open)) {
						Dates otherDates = InvoiceCalendar.dates(combination[0], combination[1], other);
						boolean inside = !day.isBefore(otherDates.periodStart()) && !day.isAfter(otherDates.closing());
						assertThat(inside).as(label + " vs " + other).isFalse();
					}
				}
			}
		}
	}

	@Test
	void daysOutsideOneToThirtyOneAreRejected() {
		assertThatThrownBy(() -> InvoiceCalendar.dates(0, 10, YearMonth.of(2024, 11)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> InvoiceCalendar.dates(10, 32, YearMonth.of(2024, 11)))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
