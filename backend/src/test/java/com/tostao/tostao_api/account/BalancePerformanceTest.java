package com.tostao.tostao_api.account;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** SC-007: 50 contas e 20.000 lançamentos listam com saldos em menos de 1 segundo. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BalancePerformanceTest {

	private static final long LIMIT_MS = 1_000;

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	@Test
	void fiftyAccountsAndTwentyThousandTransactionsRespondUnderOneSecond() throws Exception {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		Fixture fx = fixtures.workspaceWithOwner();
		for (int i = 0; i < 50; i++) {
			fixtures.insertAccount(fx.workspaceId(), "Conta %02d".formatted(i), "CHECKING", 1_000, false);
		}
		// 400 lançamentos por conta: 200 de +1000 e 200 de -400, ou seja, +120.000 por conta
		int inserted = owner.client().sql("""
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description,
				                          created_by)
				select a.workspace_id, a.id,
				       case when g % 2 = 0 then 'INCOME' else 'EXPENSE' end,
				       case when g % 2 = 0 then 1000 else -400 end,
				       current_date - (g % 365), 'carga', :user
				from accounts a cross join generate_series(1, 400) g
				where a.workspace_id = :workspace
				""").param("user", fx.userId()).param("workspace", fx.workspaceId()).update();
		assertThat(inserted).isEqualTo(20_000);

		String accounts = "/api/v1/workspaces/" + fx.workspaceId() + "/accounts";
		String report = "/api/v1/workspaces/" + fx.workspaceId() + "/reports/balance";
		long accountsMs = medianMillis(accounts, fx);
		long reportMs = medianMillis(report, fx);

		assertThat(accountsMs).as("GET /accounts, mediana em ms").isLessThan(LIMIT_MS);
		assertThat(reportMs).as("GET /reports/balance, mediana em ms").isLessThan(LIMIT_MS);
		// 50 contas de 1.000 + 120.000 cada = 6.050.000
		mvc.perform(get(report).cookie(session(fx.token())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accounts.length()").value(50))
				.andExpect(jsonPath("$.totalBalanceCents").value(6_050_000));
	}

	/** Uma chamada de aquecimento e a mediana de cinco. */
	private long medianMillis(String path, Fixture fx) throws Exception {
		mvc.perform(get(path).cookie(session(fx.token()))).andExpect(status().isOk());
		List<Long> samples = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			long start = System.nanoTime();
			mvc.perform(get(path).cookie(session(fx.token()))).andExpect(status().isOk());
			samples.add((System.nanoTime() - start) / 1_000_000);
		}
		Collections.sort(samples);
		return samples.get(2);
	}

}
