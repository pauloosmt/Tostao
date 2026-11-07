package com.tostao.tostao_api.dashboard;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.tostao.tostao_api.common.error.FieldValidationException;

/**
 * Período do painel: um mês (do dia 1 ao último) ou uma semana ISO 8601 (de segunda a domingo), com os
 * dois limites incluídos. Regra pura, sem Spring nem banco. Entrada malformada vira
 * {@link FieldValidationException} (400 com o campo), nunca outra exceção.
 */
public final class Period {

	/** Primeiro e último ano aceitos (o ano ISO, no caso da semana). */
	static final int MIN_YEAR = 2000;
	static final int MAX_YEAR = 2100;
	/** Fuso só para decidir o mês corrente: o produto é em BRL e o workspace ainda não tem fuso. */
	static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
	/** A semana começa na segunda-feira (ISO 8601). */
	private static final DayOfWeek WEEK_START = DayOfWeek.MONDAY;

	private static final Pattern MONTH_FORMAT = Pattern.compile("(\\d{4})-(\\d{2})");
	private static final Pattern WEEK_FORMAT = Pattern.compile("(\\d{4})-W(\\d{2})");

	private final PeriodType type;
	private final String id;
	private final LocalDate start;
	private final LocalDate end;

	private Period(PeriodType type, String id, LocalDate start, LocalDate end) {
		this.type = type;
		this.id = id;
		this.start = start;
		this.end = end;
	}

	/** Mês no formato {@code AAAA-MM}, de 2000-01 a 2100-12. */
	public static Period ofMonth(String text) {
		Matcher m = text == null ? null : MONTH_FORMAT.matcher(text);
		if (m == null || !m.matches()) {
			throw invalidMonth();
		}
		int year = Integer.parseInt(m.group(1));
		int month = Integer.parseInt(m.group(2));
		if (year < MIN_YEAR || year > MAX_YEAR || month < 1 || month > 12) {
			throw invalidMonth();
		}
		return monthOf(LocalDate.of(year, month, 1));
	}

	/** Semana no formato {@code AAAA-Www}, ano ISO de 2000 a 2100, de uma semana que exista nesse ano. */
	public static Period ofWeek(String text) {
		Matcher m = text == null ? null : WEEK_FORMAT.matcher(text);
		if (m == null || !m.matches()) {
			throw invalidWeek();
		}
		int year = Integer.parseInt(m.group(1));
		int week = Integer.parseInt(m.group(2));
		if (year < MIN_YEAR || year > MAX_YEAR) {
			throw invalidWeek();
		}
		// 28/12 pertence sempre à última semana ISO do ano
		int weeksInYear = LocalDate.of(year, 12, 28).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
		if (week < 1 || week > weeksInYear) {
			throw invalidWeek();
		}
		LocalDate monday = LocalDate.of(year, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week).with(WEEK_START);
		return weekOf(monday);
	}

	/**
	 * Escolhe o período: só {@code month}, só {@code week} ou, sem nenhum dos dois, o mês corrente no
	 * fuso de Brasília. Texto vazio é valor inválido, nunca "ausente".
	 */
	public static Period resolve(String month, String week, Instant now) {
		if (month != null && week != null) {
			throw new FieldValidationException("week", "informe apenas month ou week, não os dois");
		}
		if (month != null) {
			return ofMonth(month);
		}
		if (week != null) {
			return ofWeek(week);
		}
		return monthOf(LocalDate.ofInstant(now, ZONE).withDayOfMonth(1));
	}

	/**
	 * A semana pedida ou, sem {@code week}, a semana ISO que contém hoje no fuso de Brasília. Texto vazio é
	 * valor inválido, nunca "ausente".
	 */
	public static Period resolveWeek(String week, Instant now) {
		if (week != null) {
			return ofWeek(week);
		}
		return weekOf(LocalDate.ofInstant(now, ZONE).with(WEEK_START));
	}

	/** Segunda-feira da última semana aceita (2100-W52). */
	public static final LocalDate LAST_WEEK_START = LocalDate.of(2100, 12, 27);

	/** A semana ISO que contém a data dada (sem validar a faixa 2000 a 2100). */
	public static Period containing(LocalDate date) {
		return weekOf(date.with(WEEK_START));
	}

	/** Mês anterior, ou a semana de 7 dias antes. Pode cair antes de 2000 (então não tem lançamentos). */
	public Period previous() {
		return type == PeriodType.MONTH ? monthOf(start.minusMonths(1)) : weekOf(start.minusDays(7));
	}

	/** Todos os dias do período, em ordem de calendário. */
	public List<LocalDate> days() {
		List<LocalDate> days = new ArrayList<>();
		for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
			days.add(d);
		}
		return days;
	}

	public PeriodType type() {
		return type;
	}

	public String id() {
		return id;
	}

	public LocalDate start() {
		return start;
	}

	public LocalDate end() {
		return end;
	}

	private static Period monthOf(LocalDate firstDay) {
		String id = "%04d-%02d".formatted(firstDay.getYear(), firstDay.getMonthValue());
		return new Period(PeriodType.MONTH, id, firstDay, firstDay.withDayOfMonth(firstDay.lengthOfMonth()));
	}

	private static Period weekOf(LocalDate monday) {
		String id = "%04d-W%02d".formatted(monday.get(IsoFields.WEEK_BASED_YEAR),
				monday.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
		return new Period(PeriodType.WEEK, id, monday, monday.plusDays(6));
	}

	private static FieldValidationException invalidMonth() {
		return new FieldValidationException("month", "mês inválido: use AAAA-MM entre 2000-01 e 2100-12");
	}

	private static FieldValidationException invalidWeek() {
		return new FieldValidationException("week",
				"semana inválida: use AAAA-Www, uma semana ISO existente entre 2000 e 2100");
	}

}
