package com.tostao.tostao_api.dashboard;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.dashboard.DashboardRules.CategoryRow;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse.Balances;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse.Change;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse.Comparison;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse.DayPoint;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse.PeriodInfo;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse.Totals;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/**
 * Painel de um período (mês ou semana ISO) de um workspace. Só lê. Duas camadas de isolamento:
 * {@link WorkspaceScoped} (participação) e o RLS cuidam do workspace, e toda consulta daqui também filtra
 * explicitamente por {@code workspace_id}. Tudo sai de uma só transação {@code READ ONLY} com
 * {@code REPEATABLE READ}, então totais, grupos, dias, comparação e saldos vêm do mesmo instante.
 */
@Service
public class DashboardService {

	/**
	 * Receita e despesa por dia (sem transferências e sem excluídos). Despesa sai positiva. A soma de
	 * {@code bigint} no Postgres é {@code numeric}; volta a {@code bigint}.
	 */
	private static final String DAILY_SQL = """
			select occurred_on,
			       coalesce(sum(amount_cents) filter (where type = 'INCOME'), 0)::bigint as income,
			       coalesce(-sum(amount_cents) filter (where type = 'EXPENSE'), 0)::bigint as expense
			from transactions
			where workspace_id = :workspaceId and deleted_at is null and type <> 'TRANSFER'
			  and occurred_on between :from and :to
			group by occurred_on
			""";

	/**
	 * Receita e despesa por categoria no período: {@code root} é a categoria principal (a própria categoria,
	 * ou o pai dela). Sem categoria, as colunas de categoria vêm nulas. Categorias arquivadas contam: nenhum
	 * filtro de {@code archived}. A hierarquia tem só dois níveis, então o {@code coalesce} basta.
	 */
	private static final String CATEGORY_SQL = """
			select t.type, root.id as root_id, root.name as root_name, c.id as cat_id, c.name as cat_name,
			       sum(case t.type when 'EXPENSE' then -t.amount_cents else t.amount_cents end)::bigint as total
			from transactions t
			left join categories c on c.id = t.category_id and c.workspace_id = t.workspace_id
			left join categories root on root.id = coalesce(c.parent_id, c.id) and root.workspace_id = t.workspace_id
			where t.workspace_id = :workspaceId and t.deleted_at is null and t.type <> 'TRANSFER'
			  and t.occurred_on between :from and :to
			group by t.type, root.id, root.name, c.id, c.name
			""";

	private record DayRow(LocalDate date, long income, long expense) {
	}

	private record TypedRow(String type, CategoryRow row) {
	}

	private final JdbcClient jdbc;

	public DashboardService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@WorkspaceScoped
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public DashboardResponse dashboard(UUID workspaceId, Period period) {
		Period previous = period.previous();
		List<DayRow> rows = jdbc.sql(DAILY_SQL)
				.param("workspaceId", workspaceId)
				.param("from", previous.start())
				.param("to", period.end())
				.query((rs, i) -> new DayRow(rs.getObject("occurred_on", LocalDate.class), rs.getLong("income"),
						rs.getLong("expense")))
				.list();

		// As linhas cobrem o período anterior e o atual, que são contíguos: tudo antes do início é "anterior".
		long income = 0;
		long expense = 0;
		long previousIncome = 0;
		long previousExpense = 0;
		Map<LocalDate, DayRow> byDay = new HashMap<>();
		for (DayRow row : rows) {
			if (row.date().isBefore(period.start())) {
				previousIncome = Math.addExact(previousIncome, row.income());
				previousExpense = Math.addExact(previousExpense, row.expense());
			} else {
				income = Math.addExact(income, row.income());
				expense = Math.addExact(expense, row.expense());
				byDay.put(row.date(), row);
			}
		}
		List<DayPoint> daily = new ArrayList<>();
		for (LocalDate day : period.days()) {
			DayRow row = byDay.get(day);
			daily.add(new DayPoint(day, row == null ? 0 : row.income(), row == null ? 0 : row.expense(), 0));
		}
		long result = Math.subtractExact(income, expense);
		Comparison comparison = new Comparison(info(previous),
				change(income, previousIncome),
				change(expense, previousExpense),
				change(result, Math.subtractExact(previousIncome, previousExpense)));

		List<TypedRow> categoryRows = jdbc.sql(CATEGORY_SQL)
				.param("workspaceId", workspaceId)
				.param("from", period.start())
				.param("to", period.end())
				.query((rs, i) -> new TypedRow(rs.getString("type"),
						new CategoryRow(rs.getObject("root_id", UUID.class), rs.getString("root_name"),
								rs.getObject("cat_id", UUID.class), rs.getString("cat_name"), rs.getLong("total"))))
				.list();
		List<CategoryRow> incomeRows = rowsOf(categoryRows, "INCOME");
		List<CategoryRow> expenseRows = rowsOf(categoryRows, "EXPENSE");

		return new DashboardResponse(
				info(period),
				new Totals(income, expense, result),
				DashboardRules.groups(incomeRows, income),
				DashboardRules.groups(expenseRows, expense),
				daily,
				comparison,
				new Balances(List.of(), 0, 0, 0));
	}

	/** Variação do período sobre o anterior, em centavos e em percentual (nulo se o anterior é zero). */
	private static Change change(long current, long previous) {
		long delta = Math.subtractExact(current, previous);
		return new Change(previous, delta, DashboardRules.changePercent(delta, previous));
	}

	private static List<CategoryRow> rowsOf(List<TypedRow> rows, String type) {
		List<CategoryRow> result = new ArrayList<>();
		for (TypedRow row : rows) {
			if (row.type().equals(type)) {
				result.add(row.row());
			}
		}
		return result;
	}

	private static PeriodInfo info(Period period) {
		return new PeriodInfo(period.type(), period.id(), period.start(), period.end());
	}

}
