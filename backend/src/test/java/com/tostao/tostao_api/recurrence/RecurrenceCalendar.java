package com.tostao.tostao_api.recurrence;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * O calendário das ocorrências escrito de forma independente da função de banco, só para os testes: aritmética de ano
 * e mês (sem {@code plusMonths}), sempre a partir do início, com o dia limitado ao fim do mês.
 */
final class RecurrenceCalendar {

	static final LocalDate LIMIT = LocalDate.of(2100, 12, 31);

	private RecurrenceCalendar() {
	}

	/** Soma meses a uma data, limitando o dia ao último do mês de chegada. */
	static LocalDate addMonths(LocalDate base, int months) {
		int total = base.getYear() * 12 + (base.getMonthValue() - 1) + months;
		YearMonth target = YearMonth.of(total / 12, total % 12 + 1);
		return target.atDay(Math.min(base.getDayOfMonth(), target.lengthOfMonth()));
	}

	/** A data da ocorrência {@code n} (a partir de 0). */
	static LocalDate date(LocalDate start, String frequency, int n) {
		return switch (frequency) {
			case "WEEKLY" -> LocalDate.ofEpochDay(start.toEpochDay() + 7L * n);
			case "MONTHLY" -> addMonths(start, n);
			default -> addMonths(start, 12 * n);
		};
	}

	/**
	 * As datas esperadas, na ordem do número: de 0 até a primeira que passa de 12 meses depois de {@code today}, do fim
	 * ({@code end}, se houver) ou de 2100-12-31.
	 */
	static List<LocalDate> expected(LocalDate start, String frequency, LocalDate end, LocalDate today) {
		LocalDate horizon = addMonths(today, 12);
		List<LocalDate> dates = new ArrayList<>();
		for (int n = 0; n < 10_000; n++) {
			LocalDate date = date(start, frequency, n);
			if (date.isAfter(horizon) || (end != null && date.isAfter(end)) || date.isAfter(LIMIT)) {
				break;
			}
			dates.add(date);
		}
		return dates;
	}

}
