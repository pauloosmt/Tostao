package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** SC-008: uma página de um workspace com 20.000 lançamentos responde em menos de 1 segundo, com ou sem filtros. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TransactionPerformanceTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private long medianMillis(Fixture fx, Supplier<MockHttpServletRequestBuilder> request) throws Exception {
		mvc.perform(request.get().cookie(session(fx.token()))).andExpect(status().isOk());
		long[] millis = new long[5];
		for (int i = 0; i < millis.length; i++) {
			long start = System.nanoTime();
			mvc.perform(request.get().cookie(session(fx.token()))).andExpect(status().isOk());
			millis[i] = (System.nanoTime() - start) / 1_000_000;
		}
		Arrays.sort(millis);
		return millis[millis.length / 2];
	}

	@Test
	void pagesOverTwentyThousandTransactionsRespondInUnderASecond() throws Exception {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID[] accounts = new UUID[5];
		for (int i = 0; i < accounts.length; i++) {
			accounts[i] = fixtures.insertAccount(w, "Conta " + i, "CHECKING", 0, false);
		}
		UUID food = fixtures.insertCategory(w, null, "Comida", "EXPENSE", false);
		for (int i = 0; i < 8; i++) {
			fixtures.insertCategory(w, food, "Sub " + i, "EXPENSE", false);
		}
		UUID salary = fixtures.insertCategory(w, null, "Salario", "INCOME", false);

		// 16.000 despesas (uma em cada 50 excluída), 2.000 receitas e 1.000 transferências (2.000 pontas)
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

		String base = "/api/v1/workspaces/" + w + "/transactions";
		long total = owner.client().sql("select count(*) from transactions where workspace_id = :w and deleted_at is null")
				.param("w", w).query(Long.class).single();
		assertThat(total).isEqualTo(15_680 + 2_000 + 2_000);

		long plain = medianMillis(fx, () -> get(base).param("size", "20"));
		long filtered = medianMillis(fx, () -> get(base).param("from", "2024-06-01").param("to", "2024-09-30")
				.param("accountId", accounts[2].toString()));
		long byCategory = medianMillis(fx, () -> get(base).param("categoryId", food.toString()));
		long byText = medianMillis(fx, () -> get(base).param("q", "compra 1"));
		long deepPage = medianMillis(fx, () -> get(base).param("page", "150").param("size", "100"));

		System.out.printf("[SC-008] 20.000 lançamentos, mediana em ms: sem filtro=%d, período+conta=%d, "
				+ "categoria principal=%d, texto=%d, página 150=%d%n", plain, filtered, byCategory, byText, deepPage);
		owner.client().sql("""
				explain (analyze, buffers, format text)
				select t.id from transactions t
				where t.workspace_id = :w and t.deleted_at is null
				  and t.occurred_on >= date '2024-06-01' and t.occurred_on <= date '2024-09-30'
				  and t.account_id = :a
				order by t.occurred_on desc, t.created_at desc, t.id desc limit 20""")
				.param("w", w).param("a", accounts[2]).query(String.class).list()
				.forEach(line -> System.out.println("[SC-008 explain] " + line));
		assertThat(plain).as("sem filtro").isLessThan(1000);
		assertThat(filtered).as("período + conta").isLessThan(1000);
		assertThat(byCategory).as("categoria principal").isLessThan(1000);
		assertThat(byText).as("texto").isLessThan(1000);
		assertThat(deepPage).as("página 150").isLessThan(1000);

		String json = mvc.perform(get(base).param("size", "100").param("page", "197").cookie(session(fx.token())))
				.andReturn().getResponse().getContentAsString();
		assertThat((Integer) JsonPath.read(json, "$.totalPages")).isEqualTo(197);
		assertThat((Integer) JsonPath.read(json, "$.items.length()")).isEqualTo(80);
	}

}
