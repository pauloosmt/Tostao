package com.tostao.tostao_api.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.AuthSupport;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** SC-012: com 20.000 lançamentos, uma parte pendente, as consultas continuam abaixo de 1 segundo. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PendingPerformanceTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private interface Call {
		ResultActions run() throws Exception;
	}

	private long medianMillis(Call call) throws Exception {
		call.run().andExpect(status().isOk());
		long[] millis = new long[5];
		for (int i = 0; i < millis.length; i++) {
			long start = System.nanoTime();
			call.run().andExpect(status().isOk());
			millis[i] = (System.nanoTime() - start) / 1_000_000;
		}
		Arrays.sort(millis);
		return millis[millis.length / 2];
	}

	@Test
	void everyQueryStaysUnderASecondWithTwentyThousandTransactionsAndAPendingShare() throws Exception {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID card = fixtures.insertCard(w, "Visa", 0, 5_000_000L, 20, 28, false);
		UUID food = fixtures.insertCategory(w, null, "Mercado", "EXPENSE", false);
		UUID salary = fixtures.insertCategory(w, null, "Salario", "INCOME", false);
		for (int i = 0; i < 4; i++) {
			fixtures.insertAccount(w, "Conta " + i, "CHECKING", 10_000, false);
		}
		fixtures.insertMonthlyBudget(w, java.time.LocalDate.of(2025, 6, 1), null, 5_000_000, fx.userId());
		fixtures.insertMonthlyBudget(w, java.time.LocalDate.of(2025, 6, 1), food, 2_000_000, fx.userId());

		// ~13% pendentes: 14.000 despesas nas 4 contas, 2.000 receitas, 2.000 no cartão e 1.000 transferências (2.000 pontas)
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, status)
				select :w, (select id from accounts where workspace_id = :w and name = 'Conta ' || (g % 4)),
				       :food, 'EXPENSE', -(100 + g % 900), date '2024-01-01' + (g % 1095), 'Compra ' || g, :u,
				       case when g % 8 = 0 then 'PENDING' else 'CLEARED' end
				from generate_series(1, 14000) g""").param("w", w).param("food", food).param("u", fx.userId()).update();
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, status)
				select :w, (select id from accounts where workspace_id = :w and name = 'Conta ' || (g % 4)),
				       :salary, 'INCOME', 1000 + g, date '2024-01-01' + (g % 1095), 'Salario ' || g, :u,
				       case when g % 8 = 0 then 'PENDING' else 'CLEARED' end
				from generate_series(1, 2000) g""").param("w", w).param("salary", salary).param("u", fx.userId()).update();
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, status)
				select :w, :card, :food, 'EXPENSE', -(100 + g % 900), date '2024-01-01' + (g % 1095), 'Cartao ' || g, :u,
				       case when g % 8 = 0 then 'PENDING' else 'CLEARED' end
				from generate_series(1, 2000) g""").param("w", w).param("card", card).param("food", food).param("u", fx.userId()).update();
		owner.client().sql("""
				with g as (select gen_random_uuid() as grp, n from generate_series(1, 1000) n),
				     a as (select min(id::text)::uuid as a1, max(id::text)::uuid as a2 from accounts
				           where workspace_id = :w and name like 'Conta %')
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description,
				                          transfer_group_id, created_by, status)
				select :w, case when v.s < 0 then a.a1 else a.a2 end, 'TRANSFER', v.s * (500 + g.n),
				       date '2024-01-01' + (g.n % 1095), 'Transferencia ' || g.n, g.grp, :u,
				       case when g.n % 8 = 0 then 'PENDING' else 'CLEARED' end
				from g cross join a cross join (values (-1), (1)) as v(s)""").param("w", w).param("u", fx.userId()).update();
		owner.client().sql("analyze transactions").update();

		long total = owner.client().sql("select count(*) from transactions where workspace_id = :w").param("w", w)
				.query(Long.class).single();
		long pending = owner.client().sql("select count(*) from transactions where workspace_id = :w and status = 'PENDING'")
				.param("w", w).query(Long.class).single();
		assertThat(total).isGreaterThanOrEqualTo(20_000);
		assertThat(pending).isGreaterThan(2_000);

		String base = "/api/v1/workspaces/" + w;
		long list = medianMillis(() -> mvc.perform(get(base + "/transactions?size=100").cookie(AuthSupport.session(fx.token()))));
		long listPending = medianMillis(() -> mvc.perform(get(base + "/transactions?size=100&status=PENDING").cookie(AuthSupport.session(fx.token()))));
		long accounts = medianMillis(() -> mvc.perform(get(base + "/accounts").cookie(AuthSupport.session(fx.token()))));
		long report = medianMillis(() -> mvc.perform(get(base + "/reports/balance").cookie(AuthSupport.session(fx.token()))));
		long dashboard = medianMillis(() -> mvc.perform(get(base + "/dashboard?month=2025-06").cookie(AuthSupport.session(fx.token()))));
		long budget = medianMillis(() -> mvc.perform(get(base + "/monthly-budgets?month=2025-06").cookie(AuthSupport.session(fx.token()))));
		long weekly = medianMillis(() -> mvc.perform(get(base + "/budgets?week=2025-W26").cookie(AuthSupport.session(fx.token()))));
		long invoice = medianMillis(() -> mvc.perform(get(base + "/accounts/" + card + "/invoices/2025-06").cookie(AuthSupport.session(fx.token()))));
		long summary = medianMillis(() -> mvc.perform(get(base + "/accounts/" + card + "/card-summary").cookie(AuthSupport.session(fx.token()))));
		System.out.printf("[SC-012 pendente] %d lançamentos (%d pendentes), medianas em ms: lista=%d, lista pendentes=%d, "
				+ "contas=%d, saldos=%d, dashboard=%d, orçamento mensal=%d, semanal=%d, fatura=%d, resumo=%d%n", total, pending,
				list, listPending, accounts, report, dashboard, budget, weekly, invoice, summary);

		assertThat(list).as("lista").isLessThan(1000);
		assertThat(listPending).as("lista de pendentes").isLessThan(1000);
		assertThat(accounts).as("contas").isLessThan(1000);
		assertThat(report).as("saldos").isLessThan(1000);
		assertThat(dashboard).as("dashboard").isLessThan(1000);
		assertThat(budget).as("orçamento mensal").isLessThan(1000);
		assertThat(weekly).as("orçamento semanal").isLessThan(1000);
		assertThat(invoice).as("fatura").isLessThan(1000);
		assertThat(summary).as("resumo do cartão").isLessThan(1000);
	}

}
