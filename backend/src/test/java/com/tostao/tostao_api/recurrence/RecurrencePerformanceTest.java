package com.tostao.tostao_api.recurrence;

import static com.tostao.tostao_api.recurrence.RecurrenceScenario.today;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.automation.AutomationService;
import com.tostao.tostao_api.automation.AutomationService.RunResult;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * SC-013: com 20.000 lançamentos, 200 recorrências e 2.000 lançamentos vencidos com marcador, uma execução automática
 * termina em menos de 5 s, e criar uma recorrência, consultá-la e listar lançamentos respondem em menos de 1 s cada.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RecurrencePerformanceTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	AutomationService automation;

	/** O banco é compartilhado: deixa de lado os lançamentos marcados e as recorrências que sobraram de outros testes. */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
		owner.client().sql("select auto_clear_due(date '2100-12-31')").query(Integer.class).single();
	}

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
	void theRunAndTheRoutesStayFastWithTwentyThousandTransactionsAndTwoHundredRecurrences() throws Exception {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID food = fixtures.insertCategory(w, null, "Mercado", "EXPENSE", false);
		UUID rent = fixtures.insertCategory(w, null, "Aluguel", "EXPENSE", false);
		for (int i = 0; i < 4; i++) {
			fixtures.insertAccount(w, "Conta " + i, "CHECKING", 10_000, false);
		}

		// 18.000 lançamentos comuns, 4 anos, uma parte pendente
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, status)
				select :w, (select id from accounts where workspace_id = :w and name = 'Conta ' || (g % 4)),
				       :food, 'EXPENSE', -(100 + g % 900), date '2024-01-01' + (g % 1460), 'Compra ' || g, :u,
				       case when g % 8 = 0 then 'PENDING' else 'CLEARED' end
				from generate_series(1, 18000) g""").param("w", w).param("food", food).param("u", fx.userId()).update();
		// 2.000 pendentes vencidos com o marcador de efetivação automática
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, status, auto_clear)
				select :w, (select id from accounts where workspace_id = :w and name = 'Conta ' || (g % 4)),
				       :food, 'EXPENSE', -(100 + g % 900), :today - (g % 300), 'Marcado ' || g, :u, 'PENDING', true
				from generate_series(1, 2000) g""").param("w", w).param("food", food).param("u", fx.userId())
				.param("today", today()).update();
		// 200 recorrências ativas, mensais e semanais, com início de até 200 dias atrás
		owner.client().sql("""
				insert into recurrences (workspace_id, account_id, category_id, type, amount_cents, description,
				                         frequency, start_on, auto_clear, created_by)
				select :w, (select id from accounts where workspace_id = :w and name = 'Conta ' || (g % 4)),
				       :rent, 'EXPENSE', 1000 + g, 'Recorrencia ' || g, case when g % 2 = 0 then 'WEEKLY' else 'MONTHLY' end,
				       :today - (g % 200), g % 3 = 0, :u
				from generate_series(1, 200) g""").param("w", w).param("rent", rent).param("u", fx.userId())
				.param("today", today()).update();
		owner.client().sql("analyze transactions").update();
		owner.client().sql("analyze recurrences").update();

		// a primeira execução gera as ocorrências e efetiva os 2.000 vencidos; a segunda não tem o que fazer
		long start = System.nanoTime();
		RunResult first = automation.runOnce(today());
		long firstMillis = (System.nanoTime() - start) / 1_000_000;
		start = System.nanoTime();
		RunResult second = automation.runOnce(today());
		long secondMillis = (System.nanoTime() - start) / 1_000_000;
		assertThat(first.skipped()).isFalse();
		assertThat(first.generated()).isGreaterThan(2_000);
		assertThat(first.cleared()).isGreaterThanOrEqualTo(2_000);
		assertThat(second).isEqualTo(new RunResult(false, 0, 0));
		long total = owner.client().sql("select count(*) from transactions where workspace_id = :w").param("w", w)
				.query(Long.class).single();
		assertThat(total).isGreaterThanOrEqualTo(20_000);

		UUID account = owner.client().sql("select id from accounts where workspace_id = :w and name = 'Conta 0'")
				.param("w", w).query(UUID.class).single();
		String base = "/api/v1/workspaces/" + w;
		String body = "{\"accountId\":\"%s\",\"categoryId\":\"%s\",\"type\":\"EXPENSE\",\"amountCents\":5000,\"description\":\"Semanal\",\"frequency\":\"WEEKLY\",\"startOn\":\"%s\"}"
				.formatted(account, food, today());
		java.util.List<String> ids = new java.util.ArrayList<>();
		long create = medianMillis(() -> {
			ResultActions result = mvc.perform(post(base + "/recurrences").cookie(session(fx.token()))
					.contentType(MediaType.APPLICATION_JSON).content(body));
			ids.add(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
			return result;
		}, 201);
		String id = ids.get(ids.size() - 1);
		long detail = medianMillis(() -> mvc.perform(get(base + "/recurrences/" + id).cookie(session(fx.token()))), 200);
		long list = medianMillis(() -> mvc.perform(get(base + "/recurrences").cookie(session(fx.token()))), 200);
		long transactions = medianMillis(() -> mvc.perform(get(base + "/transactions?size=100").cookie(session(fx.token()))), 200);
		System.out.printf("[SC-013 recorrencias] %d lançamentos, execução 1=%d ms (%d geradas, %d efetivadas), execução 2=%d ms; "
				+ "medianas em ms: criar=%d, detalhe=%d, lista=%d, lançamentos=%d%n", total, firstMillis, first.generated(),
				first.cleared(), secondMillis, create, detail, list, transactions);

		assertThat(firstMillis).as("primeira execução").isLessThan(5_000);
		assertThat(secondMillis).as("segunda execução").isLessThan(5_000);
		assertThat(create).as("criar").isLessThan(1_000);
		assertThat(detail).as("detalhe").isLessThan(1_000);
		assertThat(list).as("lista").isLessThan(1_000);
		assertThat(transactions).as("lançamentos").isLessThan(1_000);
	}

}
