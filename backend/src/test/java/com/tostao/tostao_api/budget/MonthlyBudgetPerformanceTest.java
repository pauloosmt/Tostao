package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.month;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** SC-011: o mês de um workspace com 20.000 lançamentos e 40 limites responde em menos de 1 segundo. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MonthlyBudgetPerformanceTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private long medianMillis(Fixture fx, String query) throws Exception {
		month(mvc, fx, query).andExpect(status().isOk());
		long[] millis = new long[5];
		for (int i = 0; i < millis.length; i++) {
			long start = System.nanoTime();
			month(mvc, fx, query).andExpect(status().isOk());
			millis[i] = (System.nanoTime() - start) / 1_000_000;
		}
		Arrays.sort(millis);
		return millis[millis.length / 2];
	}

	@Test
	void theMonthOverTwentyThousandTransactionsAndFortyLimitsRespondsInUnderASecond() throws Exception {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		for (int i = 0; i < 5; i++) {
			fixtures.insertAccount(w, "Conta " + i, "CHECKING", 1_000 * i, false);
		}
		// 30 categorias principais e 9 subcategorias de despesa (a última arquivada) e uma de receita
		UUID food = null;
		for (int i = 0; i < 30; i++) {
			UUID root = fixtures.insertCategory(w, null, "Categoria %02d".formatted(i), "EXPENSE", false);
			food = i == 0 ? root : food;
		}
		for (int i = 0; i < 9; i++) {
			fixtures.insertCategory(w, food, "Sub %d".formatted(i), "EXPENSE", i == 8);
		}
		UUID salary = fixtures.insertCategory(w, null, "Salario", "INCOME", false);

		// 16.000 despesas (uma em cada 50 excluída, uma em cada 40 sem categoria), 2.000 receitas e 1.000
		// transferências (2.000 pontas), espalhadas por 3 anos (2024-01-01 a 2026-12-30)
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, deleted_at)
				select :w,
				       (select id from accounts where workspace_id = :w order by name offset (g % 5) limit 1),
				       case when g % 40 = 0 then null else
				         (select id from categories where workspace_id = :w and kind = 'EXPENSE'
				          order by name, id offset (g % 39) limit 1) end,
				       'EXPENSE', -(100 + g % 900), date '2024-01-01' + (g % 1095), 'Compra ' || g, :u,
				       case when g % 50 = 0 then now() else null end
				from generate_series(1, 16000) g""")
				.param("w", w).param("u", fx.userId()).update();
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by)
				select :w, (select id from accounts where workspace_id = :w order by name offset (g % 5) limit 1),
				       :salary, 'INCOME', 5000 + g, date '2024-01-01' + (g % 1095), 'Salario ' || g, :u
				from generate_series(1, 2000) g""")
				.param("w", w).param("u", fx.userId()).param("salary", salary).update();
		owner.client().sql("""
				with g as (select gen_random_uuid() as grp, n from generate_series(1, 1000) n),
				     a as (select min(id::text)::uuid as a1, max(id::text)::uuid as a2 from accounts
				           where workspace_id = :w)
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description,
				                          transfer_group_id, created_by)
				select :w, case when v.s < 0 then a.a1 else a.a2 end, 'TRANSFER', v.s * (500 + g.n),
				       date '2024-01-01' + (g.n % 1095), 'Transferencia ' || g.n, g.grp, :u
				from g cross join a cross join (values (-1), (1)) as v(s)""")
				.param("w", w).param("u", fx.userId()).update();

		// 40 limites em cada uma dos meses medidos: o geral, as 30 principais e 8 das 9 subcategorias
		List<LocalDate> firstDays = List.of(LocalDate.of(2025, 6, 1), LocalDate.of(2024, 3, 1),
				LocalDate.of(2026, 12, 1));
		for (LocalDate firstDay : firstDays) {
			owner.client().sql("""
					insert into monthly_budgets (workspace_id, month_start, category_id, amount_cents, created_by)
					select :w, :firstDay, c.id, 10000 + (row_number() over (order by c.name, c.id)) * 100, :u
					from categories c where c.workspace_id = :w and c.kind = 'EXPENSE' and not c.archived""")
					.param("w", w).param("firstDay", firstDay).param("u", fx.userId()).update();
			fixtures.insertMonthlyBudget(w, firstDay, null, 500_000, fx.userId());
		}
		owner.client().sql("analyze transactions").update();
		owner.client().sql("analyze monthly_budgets").update();

		long total = owner.client().sql("select count(*) from transactions where workspace_id = :w and deleted_at is null")
				.param("w", w).query(Long.class).single();
		assertThat(total).isEqualTo(15_680 + 2_000 + 2_000);
		long limits = owner.client().sql("select count(*) from monthly_budgets where workspace_id = :w and month_start = :s")
				.param("w", w).param("s", firstDays.get(0)).query(Long.class).single();
		assertThat(limits).isEqualTo(30 + 8 + 1);

		long anyMonth = medianMillis(fx, "month=2025-06");
		long otherMonth = medianMillis(fx, "month=2024-03");
		long lastMonth = medianMillis(fx, "month=2026-12");

		System.out.printf("[SC-011 orçamento] 20.000 lançamentos e %d limites no mês, mediana em ms: "
				+ "mês qualquer=%d, outro mês=%d, último mês=%d%n", limits, anyMonth, otherMonth, lastMonth);
		owner.client().sql("""
				explain (analyze, buffers, format text)
				select t.category_id, c.parent_id, -sum(t.amount_cents)::bigint
				from transactions t left join categories c on c.id = t.category_id and c.workspace_id = t.workspace_id
				where t.workspace_id = :w and t.deleted_at is null and t.type = 'EXPENSE'
				  and t.occurred_on between date '2025-06-01' and date '2025-06-30'
				group by t.category_id, c.parent_id""")
				.param("w", w).query(String.class).list()
				.forEach(line -> System.out.println("[SC-011 orçamento explain gastos] " + line));
		assertThat(anyMonth).as("mês qualquer").isLessThan(1000);
		assertThat(otherMonth).as("outro mês").isLessThan(1000);
		assertThat(lastMonth).as("último mês").isLessThan(1000);

		// a resposta é de verdade, não vazia
		String json = month(mvc, fx, "month=2025-06").andReturn().getResponse().getContentAsString();
		assertThat(((Number) JsonPath.read(json, "$.totalSpentCents")).longValue()).isPositive();
		assertThat(JsonPath.<List<?>>read(json, "$.budgets")).hasSize(39);
	}

}
