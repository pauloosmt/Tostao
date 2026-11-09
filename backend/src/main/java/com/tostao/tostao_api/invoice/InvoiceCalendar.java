package com.tostao.tostao_api.invoice;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Calendário da fatura do cartão (sem Spring nem banco). A fatura é identificada pelo mês do vencimento.
 *
 * <p>O dia de vencimento {@code D} cai no mês pedido; o fechamento cai no mesmo mês quando {@code D > C} e no
 * mês anterior quando {@code D <= C} (comparando os dias <b>cadastrados</b>). Dia maior que o tamanho do mês
 * vale o último dia dele. O período de uma fatura vai do dia seguinte ao fechamento da anterior até o
 * fechamento dela, ambos incluídos. Como o mês do fechamento avança de um em um junto com o mês do
 * vencimento, os fechamentos crescem estritamente e cada dia pertence a exatamente uma fatura.
 */
public final class InvoiceCalendar {

	private InvoiceCalendar() {
	}

	/** As datas de uma fatura: {@code nextClosing} é o fechamento da fatura seguinte (fim da janela de pagamentos). */
	public record Dates(YearMonth month, LocalDate periodStart, LocalDate closing, LocalDate due,
			LocalDate nextClosing) {
	}

	public static Dates dates(int closingDay, int dueDay, YearMonth dueMonth) {
		requireDay(closingDay, "closingDay");
		requireDay(dueDay, "dueDay");
		LocalDate previousClosing = closingOf(closingDay, dueDay, dueMonth.minusMonths(1));
		return new Dates(dueMonth, previousClosing.plusDays(1), closingOf(closingDay, dueDay, dueMonth),
				day(dueMonth, dueDay), closingOf(closingDay, dueDay, dueMonth.plusMonths(1)));
	}

	/**
	 * Situação: até o dia do fechamento é aberta; depois, sem restante é paga; com restante, vencida depois do
	 * dia do vencimento e fechada até ele (inclusive).
	 */
	public static InvoiceStatus status(LocalDate today, LocalDate closing, LocalDate due, long remainingCents) {
		if (!today.isAfter(closing)) {
			return InvoiceStatus.OPEN;
		}
		if (remainingCents == 0) {
			return InvoiceStatus.PAID;
		}
		return today.isAfter(due) ? InvoiceStatus.OVERDUE : InvoiceStatus.CLOSED;
	}

	/** O mês de vencimento da fatura aberta de uma data: a que tem {@code today} dentro do seu período. */
	public static YearMonth openDueMonth(int closingDay, int dueDay, LocalDate today) {
		YearMonth base = YearMonth.from(today);
		// o fechamento mais próximo de hoje cai no mês de hoje ou no seguinte, e a fatura dele vence até dois meses depois
		for (int offset = -1; offset <= 2; offset++) {
			Dates dates = dates(closingDay, dueDay, base.plusMonths(offset));
			if (!today.isBefore(dates.periodStart()) && !today.isAfter(dates.closing())) {
				return dates.month();
			}
		}
		throw new IllegalStateException("nenhuma fatura contém " + today);
	}

	private static LocalDate closingOf(int closingDay, int dueDay, YearMonth dueMonth) {
		YearMonth closingMonth = dueDay > closingDay ? dueMonth : dueMonth.minusMonths(1);
		return day(closingMonth, closingDay);
	}

	private static LocalDate day(YearMonth month, int day) {
		return month.atDay(Math.min(day, month.lengthOfMonth()));
	}

	private static void requireDay(int day, String name) {
		if (day < 1 || day > 31) {
			throw new IllegalArgumentException(name + " deve ser de 1 a 31");
		}
	}

}
