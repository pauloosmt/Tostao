package com.tostao.tostao_api.invoice;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.account.AccountService;
import com.tostao.tostao_api.account.AccountType;
import com.tostao.tostao_api.account.dto.AccountResponse;
import com.tostao.tostao_api.common.error.AccountNotFoundException;
import com.tostao.tostao_api.common.error.InvoiceNotAvailableException;
import com.tostao.tostao_api.invoice.InvoiceCalendar.Dates;
import com.tostao.tostao_api.invoice.dto.CardSummaryResponse;
import com.tostao.tostao_api.invoice.dto.CardSummaryResponse.OpenInvoice;
import com.tostao.tostao_api.invoice.dto.InvoiceLine;
import com.tostao.tostao_api.invoice.dto.InvoiceListItem;
import com.tostao.tostao_api.invoice.dto.InvoiceListResponse;
import com.tostao.tostao_api.invoice.dto.InvoiceResponse;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/**
 * Fatura do cartão de crédito: sempre calculada, nunca guardada, e só de leitura. Duas camadas de
 * isolamento: {@link WorkspaceScoped} (participação e papel) e o RLS cuidam do workspace, e toda consulta
 * daqui também filtra explicitamente por {@code workspace_id}. Cada operação lê numa única transação
 * {@code REPEATABLE READ}, então total, pago e lançamentos nunca se contradizem sob gravações simultâneas.
 *
 * <p>O valor de uma linha é {@code -amount_cents}: despesa e saída por transferência (saque) são positivas e
 * receita (estorno) é negativa. As transferências que <b>entram</b> no cartão são pagamentos: não compõem o
 * total, e contam como pago na fatura em cuja janela caem.
 */
@Service
public class InvoiceService {

	private static final String NOT_A_CARD = "A conta não é um cartão de crédito";

	/** Lançamentos que compõem a fatura: tudo do cartão no período, menos os pagamentos (transferências que entram). */
	static final String LINES_SQL = """
			select t.id, t.occurred_on, t.description, t.category_id, c.name as category_name, t.type,
			       -t.amount_cents as charge_cents
			from transactions t
			left join categories c on c.id = t.category_id and c.workspace_id = t.workspace_id
			where t.workspace_id = :workspaceId and t.account_id = :accountId and t.deleted_at is null and t.status = 'CLEARED'
			  and t.occurred_on between :start and :end
			  and not (t.type = 'TRANSFER' and t.amount_cents > 0)
			order by t.occurred_on, t.id
			""";

	/** Total de um período (a mesma regra das linhas, somada no banco). */
	static final String TOTAL_SQL = """
			select coalesce(-sum(t.amount_cents), 0)::bigint
			from transactions t
			where t.workspace_id = :workspaceId and t.account_id = :accountId and t.deleted_at is null and t.status = 'CLEARED'
			  and t.occurred_on between :start and :end
			  and not (t.type = 'TRANSFER' and t.amount_cents > 0)
			""";

	/** Pagamentos: transferências que entram no cartão depois do fechamento e até o fechamento da seguinte. */
	static final String PAID_SQL = """
			select coalesce(sum(t.amount_cents), 0)::bigint
			from transactions t
			where t.workspace_id = :workspaceId and t.account_id = :accountId and t.deleted_at is null and t.status = 'CLEARED'
			  and t.type = 'TRANSFER' and t.amount_cents > 0
			  and t.occurred_on > :closing and t.occurred_on <= :nextClosing
			""";

	/** Lançamentos do cartão agregados por dia, tipo e sentido, para montar a lista de faturas numa só leitura. */
	static final String DAILY_SQL = """
			select t.occurred_on, t.type, (t.amount_cents > 0) as inflow, sum(t.amount_cents)::bigint as cents
			from transactions t
			where t.workspace_id = :workspaceId and t.account_id = :accountId and t.deleted_at is null and t.status = 'CLEARED'
			  and t.occurred_on between :min and :max
			group by t.occurred_on, t.type, (t.amount_cents > 0)
			""";

	private final JdbcClient jdbc;
	private final AccountService accounts;

	public InvoiceService(JdbcClient jdbc, AccountService accounts) {
		this.jdbc = jdbc;
		this.accounts = accounts;
	}

	private record Card(String type, Integer closingDay, Integer dueDay) {
	}

	/** Total, pago, restante e situação de uma fatura, calculados a partir dos dois somatórios. */
	private record Numbers(long total, long paid, long remaining, InvoiceStatus status) {
	}

	/** Uma fatura com os lançamentos que a compõem. */
	@WorkspaceScoped
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public InvoiceResponse invoice(UUID workspaceId, UUID accountId, YearMonth month, LocalDate today) {
		Card card = requireCardWithDays(workspaceId, accountId);
		Dates dates = InvoiceCalendar.dates(card.closingDay(), card.dueDay(), month);

		List<InvoiceLine> lines = jdbc.sql(LINES_SQL)
				.param("workspaceId", workspaceId)
				.param("accountId", accountId)
				.param("start", dates.periodStart())
				.param("end", dates.closing())
				.query((rs, i) -> new InvoiceLine(rs.getObject("id", UUID.class),
						rs.getObject("occurred_on", LocalDate.class), rs.getString("description"),
						rs.getObject("category_id", UUID.class), rs.getString("category_name"), rs.getString("type"),
						rs.getLong("charge_cents")))
				.list();
		long total = 0;
		for (InvoiceLine line : lines) {
			total = Math.addExact(total, line.chargeCents());
		}
		long paid = today.isAfter(dates.closing()) ? jdbc.sql(PAID_SQL)
				.param("workspaceId", workspaceId)
				.param("accountId", accountId)
				.param("closing", dates.closing())
				.param("nextClosing", dates.nextClosing())
				.query(Long.class).single() : 0;
		Numbers numbers = numbers(dates, total, paid, today);
		return new InvoiceResponse(month.toString(), dates.periodStart(), dates.closing(), dates.due(),
				numbers.total(), numbers.paid(), numbers.remaining(), numbers.status(), lines);
	}

	/**
	 * Faturas de um intervalo de meses de vencimento, sem os lançamentos, do vencimento mais recente para o
	 * mais antigo. {@code from} e {@code to} nulos viram a janela padrão (seis faturas até a aberta); com só
	 * um deles, a janela tem seis meses a partir (ou até) dele.
	 */
	@WorkspaceScoped
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public InvoiceListResponse invoices(UUID workspaceId, UUID accountId, YearMonth from, YearMonth to,
			LocalDate today) {
		Card card = requireCardWithDays(workspaceId, accountId);
		YearMonth first = YearMonth.of(2000, 1);
		YearMonth last = YearMonth.of(2100, 12);
		if (from == null && to == null) {
			to = InvoiceCalendar.openDueMonth(card.closingDay(), card.dueDay(), today);
		}
		if (from == null) {
			from = max(to.minusMonths(5), first);
		}
		if (to == null) {
			to = min(from.plusMonths(5), last);
		}

		List<Dates> invoices = new ArrayList<>();
		for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
			invoices.add(InvoiceCalendar.dates(card.closingDay(), card.dueDay(), month));
		}
		LocalDate min = invoices.get(0).periodStart();
		LocalDate max = invoices.get(invoices.size() - 1).nextClosing();

		record Day(LocalDate date, String type, boolean inflow, long cents) {
		}
		List<Day> days = jdbc.sql(DAILY_SQL)
				.param("workspaceId", workspaceId)
				.param("accountId", accountId)
				.param("min", min)
				.param("max", max)
				.query((rs, i) -> new Day(rs.getObject("occurred_on", LocalDate.class), rs.getString("type"),
						rs.getBoolean("inflow"), rs.getLong("cents")))
				.list();

		List<InvoiceListItem> items = new ArrayList<>();
		for (Dates dates : invoices) {
			long total = 0;
			long paid = 0;
			for (Day day : days) {
				boolean payment = "TRANSFER".equals(day.type()) && day.inflow();
				if (!payment && !day.date().isBefore(dates.periodStart()) && !day.date().isAfter(dates.closing())) {
					total = Math.addExact(total, -day.cents());
				}
				if (payment && day.date().isAfter(dates.closing()) && !day.date().isAfter(dates.nextClosing())) {
					paid = Math.addExact(paid, day.cents());
				}
			}
			Numbers numbers = numbers(dates, total, paid, today);
			items.add(new InvoiceListItem(dates.month().toString(), dates.periodStart(), dates.closing(), dates.due(),
					numbers.total(), numbers.paid(), numbers.remaining(), numbers.status()));
		}
		Collections.reverse(items);
		return new InvoiceListResponse(items);
	}

	/**
	 * Resumo do cartão: limite, devido, disponível e a fatura aberta. O devido vem do saldo calculado pela
	 * spec 002 ({@link AccountService#get}), nunca de um segundo cálculo.
	 */
	@WorkspaceScoped
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public CardSummaryResponse summary(UUID workspaceId, UUID accountId, LocalDate today) {
		AccountResponse account = accounts.get(workspaceId, accountId);
		if (account.type() != AccountType.CREDIT_CARD) {
			throw new InvoiceNotAvailableException(NOT_A_CARD);
		}
		long owed = Math.max(Math.negateExact(account.balanceCents()), 0);
		// limite mais o saldo: a dívida (saldo negativo) reduz e um crédito a favor (saldo positivo) aumenta
		Long available = account.creditLimitCents() == null ? null
				: Math.addExact(account.creditLimitCents(), account.balanceCents());

		OpenInvoice open = null;
		if (account.closingDay() != null && account.dueDay() != null) {
			YearMonth month = InvoiceCalendar.openDueMonth(account.closingDay(), account.dueDay(), today);
			Dates dates = InvoiceCalendar.dates(account.closingDay(), account.dueDay(), month);
			long total = jdbc.sql(TOTAL_SQL)
					.param("workspaceId", workspaceId)
					.param("accountId", accountId)
					.param("start", dates.periodStart())
					.param("end", dates.closing())
					.query(Long.class).single();
			open = new OpenInvoice(month.toString(), dates.periodStart(), dates.closing(), dates.due(), total);
		}
		return new CardSummaryResponse(account.id(), account.name(), account.archived(), account.creditLimitCents(),
				owed, available, open);
	}

	/** Em fatura aberta o pago é zero; o restante nunca é negativo; a situação sai do calendário. */
	private static Numbers numbers(Dates dates, long total, long paidInWindow, LocalDate today) {
		long paid = today.isAfter(dates.closing()) ? paidInWindow : 0;
		long remaining = Math.max(Math.subtractExact(total, paid), 0);
		return new Numbers(total, paid, remaining, InvoiceCalendar.status(today, dates.closing(), dates.due(), remaining));
	}

	/** A conta existe no workspace, é cartão de crédito e tem os dois dias; senão, o motivo. */
	private Card requireCardWithDays(UUID workspaceId, UUID accountId) {
		Card card = jdbc.sql("""
				select type, closing_day, due_day from accounts
				where id = :accountId and workspace_id = :workspaceId
				""")
				.param("accountId", accountId)
				.param("workspaceId", workspaceId)
				.query((rs, i) -> new Card(rs.getString("type"), rs.getObject("closing_day", Integer.class),
						rs.getObject("due_day", Integer.class)))
				.optional()
				.orElseThrow(AccountNotFoundException::new);
		if (!"CREDIT_CARD".equals(card.type())) {
			throw new InvoiceNotAvailableException(NOT_A_CARD);
		}
		if (card.closingDay() == null || card.dueDay() == null) {
			String missing = card.closingDay() == null && card.dueDay() == null ? "closingDay e dueDay"
					: card.closingDay() == null ? "closingDay" : "dueDay";
			throw new InvoiceNotAvailableException(
					"O cartão não tem o dia de fechamento ou de vencimento cadastrado: informe " + missing);
		}
		return card;
	}

	private static YearMonth max(YearMonth a, YearMonth b) {
		return a.isAfter(b) ? a : b;
	}

	private static YearMonth min(YearMonth a, YearMonth b) {
		return a.isBefore(b) ? a : b;
	}

	/** Quantidade de meses de vencimento num intervalo, inclusive os dois extremos. */
	static long monthsBetween(YearMonth from, YearMonth to) {
		return ChronoUnit.MONTHS.between(from, to) + 1;
	}

}
