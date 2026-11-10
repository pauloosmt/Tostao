package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

/** SC-012: registrar 48 parcelas, consultar o plano e listar lançamentos, cada um abaixo de 1 s com 20.000 lançamentos. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InstallmentPerformanceTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private interface Call {
		ResultActions run() throws Exception;
	}

	/** Mediana de 5 chamadas, depois de uma de aquecimento, conferindo o status esperado em todas. */
	private long medianMillis(Call call, int expectedStatus) throws Exception {
		call.run().andExpect(status().is(expectedStatus));
		long[] millis = new long[5];
		for (int i = 0; i < millis.length; i++) {
			long start = System.nanoTime();
			call.run().andExpect(status().is(expectedStatus));
			millis[i] = (System.nanoTime() - start) / 1_000_000;
		}
		Arrays.sort(millis);
		return millis[millis.length / 2];
	}

	@Test
	void registeringAFortyEightInstallmentPurchaseTheirPlanAndTheListStayUnderASecond() throws Exception {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID card = fixtures.insertCard(w, "Visa", 0, 5_000_000L, 20, 28, false);
		UUID food = fixtures.insertCategory(w, null, "Mercado", "EXPENSE", false);
		UUID salary = fixtures.insertCategory(w, null, "Salario", "INCOME", false);
		for (int i = 0; i < 4; i++) {
			fixtures.insertAccount(w, "Conta " + i, "CHECKING", 10_000, false);
		}

		// 18.000 lançamentos comuns, 4 anos
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, status)
				select :w, (select id from accounts where workspace_id = :w and name = 'Conta ' || (g % 4)),
				       :food, 'EXPENSE', -(100 + g % 900), date '2024-01-01' + (g % 1460), 'Compra ' || g, :u,
				       case when g % 8 = 0 then 'PENDING' else 'CLEARED' end
				from generate_series(1, 16000) g""").param("w", w).param("food", food).param("u", fx.userId()).update();
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, status)
				select :w, :card, :food, 'EXPENSE', -(100 + g % 900), date '2024-01-01' + (g % 1460), 'Cartao ' || g, :u,
				       'CLEARED'
				from generate_series(1, 2000) g""").param("w", w).param("card", card).param("food", food).param("u", fx.userId()).update();
		// e 400 planos de 5 parcelas já existentes (2.000 parcelas), para o índice único ter o que indexar
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, status, installment_group_id, installment_number,
				                          installment_count)
				select :w, (select id from accounts where workspace_id = :w and name = 'Conta ' || (g.p % 4)),
				       :food, 'EXPENSE', -(1000 + g.p), date '2024-01-01' + ((g.p * 3 + n * 30) % 1460),
				       'Plano ' || g.p || ' (' || n || '/5)', :u, case when n = 1 then 'CLEARED' else 'PENDING' end,
				       g.grp, n, 5
				from (select p, gen_random_uuid() as grp from generate_series(1, 400) p) g
				cross join generate_series(1, 5) n""").param("w", w).param("food", food).param("u", fx.userId()).update();
		owner.client().sql("analyze transactions").update();
		long total = owner.client().sql("select count(*) from transactions where workspace_id = :w").param("w", w)
				.query(Long.class).single();
		assertThat(total).isGreaterThanOrEqualTo(20_000);

		UUID account = owner.client().sql("select id from accounts where workspace_id = :w and name = 'Conta 0'")
				.param("w", w).query(UUID.class).single();
		String base = "/api/v1/workspaces/" + w;
		String body = InstallmentScenario.purchaseBody(account, food, 4_800_000, 48, "2026-01-31", "Reforma", null);

		List<String> groups = new ArrayList<>();
		long register = medianMillis(() -> {
			ResultActions result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
					.post(base + "/installment-purchases").cookie(session(fx.token()))
					.contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body));
			groups.add(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.groupId"));
			return result;
		}, 201);
		String group = groups.get(groups.size() - 1);
		long plan = medianMillis(() -> mvc.perform(get(base + "/installment-purchases/" + group).cookie(session(fx.token()))), 200);
		long list = medianMillis(() -> mvc.perform(get(base + "/transactions?size=100").cookie(session(fx.token()))), 200);
		long listPending = medianMillis(
				() -> mvc.perform(get(base + "/transactions?size=100&status=PENDING").cookie(session(fx.token()))), 200);
		long accounts = medianMillis(() -> mvc.perform(get(base + "/accounts").cookie(session(fx.token()))), 200);
		long cancel = medianMillis(() -> mvc.perform(
				org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.post(base + "/installment-purchases/" + group + "/cancel").cookie(session(fx.token()))), 200);
		System.out.printf("[SC-012 parcelas] %d lançamentos, medianas em ms: registrar 48=%d, plano=%d, lista=%d, "
				+ "lista pendentes=%d, contas=%d, cancelar=%d%n", total, register, plan, list, listPending, accounts, cancel);

		assertThat(register).as("registrar 48 parcelas").isLessThan(1000);
		assertThat(plan).as("plano").isLessThan(1000);
		assertThat(list).as("lista").isLessThan(1000);
		assertThat(listPending).as("lista de pendentes").isLessThan(1000);
		assertThat(accounts).as("contas").isLessThan(1000);
		assertThat(cancel).as("cancelar").isLessThan(1000);
	}

}
