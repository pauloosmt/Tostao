package com.tostao.tostao_api.dashboard;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.dashboard.dto.DashboardResponse;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse.Balances;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse.Change;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse.Comparison;
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

	private record DayRow(LocalDate date, long income, long expense) {
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

		long income = 0;
		long expense = 0;
		for (DayRow row : rows) {
			if (!row.date().isBefore(period.start())) {
				income = Math.addExact(income, row.income());
				expense = Math.addExact(expense, row.expense());
			}
		}

		return new DashboardResponse(
				info(period),
				new Totals(income, expense, Math.subtractExact(income, expense)),
				List.of(),
				List.of(),
				List.of(),
				new Comparison(info(previous), new Change(0, 0, null), new Change(0, 0, null), new Change(0, 0, null)),
				new Balances(List.of(), 0, 0, 0));
	}

	private static PeriodInfo info(Period period) {
		return new PeriodInfo(period.type(), period.id(), period.start(), period.end());
	}

}
