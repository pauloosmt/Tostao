package com.tostao.tostao_api.dashboard;

import static com.tostao.tostao_api.dashboard.DashboardScenario.dashboard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
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

/** SC-008: o painel de um workspace com 20.000 lançamentos responde em menos de 1 segundo. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DashboardPerformanceTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private long medianMillis(Fixture fx, String query) throws Exception {
		dashboard(mvc, fx, query).andExpect(status().isOk());
		long[] millis = new long[5];
		for (int i = 0; i < millis.length; i++) {
			long start = System.nanoTime();
			dashboard(mvc, fx, query).andExpect(status().isOk());
			millis[i] = (System.nanoTime() - start) / 1_000_000;
		}
		Arrays.sort(millis);
		return millis[millis.length / 2];
	}

	@Test
	void theDashboardOverTwentyThousandTransactionsRespondsInUnderASecond() throws Exception {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID[] accounts = new UUID[5];
		for (int i = 0; i < accounts.length; i++) {
			accounts[i] = fixtures.insertAccount(w, "Conta " + i, "CHECKING", 1_000 * i, false);
		}
		UUID food = fixtures.insertCategory(w, null, "Comida", "EXPENSE", false);
		for (int i = 0; i < 8; i++) {
			fixtures.insertCategory(w, food, "Sub " + i, "EXPENSE", i == 7);
		}
		UUID salary = fixtures.insertCategory(w, null, "Salario", "INCOME", false);

		// 16.000 despesas (uma em cada 50 excluída), 2.000 receitas e 1.000 transferências (2.000 pontas),
		// espalhadas por 3 anos (2024-01-01 a 2026-12-30)
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, deleted_at)
				select :w,
				       (select id from accounts where workspace_id = :w order by name offset (g % 5) limit 1),
				       (select id from categories where workspace_id = :w and kind = 'EXPENSE'
				        order by name offset (g % 9) limit 1),
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
				with g as (select gen_random_uuid() as grp, n from generate_series(1, 1000) n)
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description,
				                          transfer_group_id, created_by)
				select :w, case when v.s < 0 then :a1 else :a2 end, 'TRANSFER', v.s * (500 + g.n),
				       date '2024-01-01' + (g.n % 1095), 'Transferencia ' || g.n, g.grp, :u
				from g cross join (values (-1), (1)) as v(s)""")
				.param("w", w).param("u", fx.userId()).param("a1", accounts[0]).param("a2", accounts[1]).update();
		owner.client().sql("analyze transactions").update();

		long total = owner.client().sql("select count(*) from transactions where workspace_id = :w and deleted_at is null")
				.param("w", w).query(Long.class).single();
		assertThat(total).isEqualTo(15_680 + 2_000 + 2_000);

		long currentMonth = medianMillis(fx, "");
		long someMonth = medianMillis(fx, "month=2025-06");
		long someWeek = medianMillis(fx, "week=2025-W26");
		long lastWeek = medianMillis(fx, "week=2026-W53");

		System.out.printf("[SC-008 painel] 20.000 lançamentos, mediana em ms: mês corrente=%d, mês qualquer=%d, "
				+ "semana qualquer=%d, última semana=%d%n", currentMonth, someMonth, someWeek, lastWeek);
		owner.client().sql("""
				explain (analyze, buffers, format text)
				select a.id, coalesce(sum(t.amount_cents) filter (where t.occurred_on < date '2025-06-01'), 0)
				from accounts a left join transactions t
				     on t.account_id = a.id and t.workspace_id = a.workspace_id and t.deleted_at is null
				where a.workspace_id = :w and not a.archived group by a.id""")
				.param("w", w).query(String.class).list()
				.forEach(line -> System.out.println("[SC-008 painel explain saldos] " + line));
		assertThat(currentMonth).as("mês corrente").isLessThan(1000);
		assertThat(someMonth).as("mês qualquer").isLessThan(1000);
		assertThat(someWeek).as("semana qualquer").isLessThan(1000);
		assertThat(lastWeek).as("última semana").isLessThan(1000);

		// a resposta é de verdade, não vazia
		String json = dashboard(mvc, fx, "month=2025-06").andReturn().getResponse().getContentAsString();
		assertThat(((Number) JsonPath.read(json, "$.totals.expenseCents")).longValue()).isPositive();
		assertThat(JsonPath.<java.util.List<?>>read(json, "$.expenseByCategory")).isNotEmpty();
	}

}
