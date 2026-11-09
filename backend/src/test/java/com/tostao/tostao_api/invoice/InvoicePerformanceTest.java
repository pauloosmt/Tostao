package com.tostao.tostao_api.invoice;

import static com.tostao.tostao_api.invoice.InvoiceScenario.invoice;
import static com.tostao.tostao_api.invoice.InvoiceScenario.list;
import static com.tostao.tostao_api.invoice.InvoiceScenario.summary;
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
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** SC-009: fatura, resumo e lista de 36 meses de um workspace com 20.000 lançamentos respondem em menos de 1 s. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InvoicePerformanceTest {

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
	void invoiceSummaryAndThirtySixMonthListOverTwentyThousandTransactionsAreUnderASecond() throws Exception {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID checking = fixtures.insertAccount(w, "Corrente", "CHECKING", 0, false);
		UUID card = fixtures.insertCard(w, "Visa", 0, 5_000_000L, 20, 28, false);
		UUID food = fixtures.insertCategory(w, null, "Mercado", "EXPENSE", false);
		UUID salary = fixtures.insertCategory(w, null, "Salario", "INCOME", false);
		for (int i = 0; i < 4; i++) {
			fixtures.insertAccount(w, "Conta " + i, "CHECKING", 0, false);
		}

		// cartão: 6.000 compras (uma em cada 50 excluída), 500 estornos, 500 pagamentos e 300 saques, em 3 anos
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, deleted_at)
				select :w, :card, :food, 'EXPENSE', -(100 + g % 900), date '2024-01-01' + (g % 1095), 'Compra ' || g,
				       :u, case when g % 50 = 0 then now() else null end
				from generate_series(1, 6000) g""")
				.param("w", w).param("card", card).param("food", food).param("u", fx.userId()).update();
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by)
				select :w, :card, :salary, 'INCOME', 100 + g, date '2024-01-01' + (g * 2 % 1095), 'Estorno ' || g, :u
				from generate_series(1, 500) g""")
				.param("w", w).param("card", card).param("salary", salary).param("u", fx.userId()).update();
		for (boolean payments : new boolean[] { true, false }) {
			owner.client().sql("""
					with g as (select gen_random_uuid() as grp, n from generate_series(1, :count) n)
					insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description,
					                          transfer_group_id, created_by)
					select :w, case when (v.s < 0) = :payments then :checking else :card end, 'TRANSFER',
					       v.s * (500 + g.n), date '2024-01-01' + (g.n * 3 % 1095), 'Transferencia ' || g.n, g.grp, :u
					from g cross join (values (-1), (1)) as v(s)""")
					.param("w", w).param("card", card).param("checking", checking).param("u", fx.userId())
					.param("payments", payments).param("count", payments ? 500 : 300).update();
		}
		// as outras contas completam os 20.000 lançamentos do workspace
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by)
				select :w, (select id from accounts where workspace_id = :w and name = 'Conta ' || (g % 4)),
				       :food, 'EXPENSE', -(100 + g % 900), date '2024-01-01' + (g % 1095), 'Compra ' || g, :u
				from generate_series(1, 12100) g""")
				.param("w", w).param("food", food).param("u", fx.userId()).update();
		owner.client().sql("analyze transactions").update();

		long total = owner.client().sql("select count(*) from transactions where workspace_id = :w")
				.param("w", w).query(Long.class).single();
		assertThat(total).isGreaterThanOrEqualTo(20_000);

		long invoiceMs = medianMillis(() -> invoice(mvc, fx, card, "2025-06"));
		long summaryMs = medianMillis(() -> summary(mvc, fx, card));
		long listMs = medianMillis(() -> list(mvc, fx, card, "from=2024-01&to=2026-12"));
		System.out.printf("Fatura: %d ms · resumo: %d ms · lista de 36 meses: %d ms (%d lançamentos)%n", invoiceMs,
				summaryMs, listMs, total);

		assertThat(invoiceMs).as("fatura de um mês").isLessThan(1000);
		assertThat(summaryMs).as("resumo").isLessThan(1000);
		assertThat(listMs).as("lista de 36 meses").isLessThan(1000);
		// e a resposta é real
		String json = list(mvc, fx, card, "from=2024-01&to=2026-12").andReturn().getResponse().getContentAsString();
		assertThat((java.util.List<?>) JsonPath.read(json, "$.invoices")).hasSize(36);
		assertThat((Integer) JsonPath.read(json, "$.invoices[10].totalCents")).isNotZero();
		String detail = invoice(mvc, fx, card, "2025-06").andReturn().getResponse().getContentAsString();
		assertThat((java.util.List<?>) JsonPath.read(detail, "$.lines")).isNotEmpty();
	}

}
