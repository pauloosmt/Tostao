package com.tostao.tostao_api.report;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BalanceReportTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private MvcResult report(Fixture fx) throws Exception {
		return mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/reports/balance")
				.cookie(session(fx.token()))).andReturn();
	}

	@Test
	void listsActiveAccountsWithBalanceAndTheTotal() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID checking = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 100_000, false);
		fixtures().insertAccount(fx.workspaceId(), "Poupança", "SAVINGS", 50_000, false);
		fixtures().insertTransaction(fx.workspaceId(), checking, fx.userId(), 15_000, false);

		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/reports/balance").cookie(session(fx.token())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accounts.length()").value(2))
				.andExpect(jsonPath("$.accounts[0].name").value("Corrente"))
				.andExpect(jsonPath("$.accounts[0].type").value("CHECKING"))
				.andExpect(jsonPath("$.accounts[0].balanceCents").value(115_000))
				.andExpect(jsonPath("$.accounts[1].balanceCents").value(50_000))
				.andExpect(jsonPath("$.totalBalanceCents").value(165_000));
	}

	@Test
	void creditCardDebtReducesTheTotal() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 100_000, false);
		fixtures().insertAccount(fx.workspaceId(), "Cartão", "CREDIT_CARD", -25_050, false);

		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/reports/balance").cookie(session(fx.token())))
				.andExpect(jsonPath("$.totalBalanceCents").value(74_950));
	}

	@Test
	void archivedAccountsAreNeitherListedNorCounted() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		fixtures().insertAccount(fx.workspaceId(), "Ativa", "CHECKING", 10_000, false);
		fixtures().insertAccount(fx.workspaceId(), "Encerrada", "CHECKING", 999_999, true);

		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/reports/balance").cookie(session(fx.token())))
				.andExpect(jsonPath("$.accounts.length()").value(1))
				.andExpect(jsonPath("$.totalBalanceCents").value(10_000));
	}

	@Test
	void aWorkspaceWithoutAccountsHasAnEmptyListAndZeroTotal() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/reports/balance").cookie(session(fx.token())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accounts.length()").value(0))
				.andExpect(jsonPath("$.totalBalanceCents").value(0));
	}

	@Test
	void totalEqualsTheSumOfTheBalancesOfTheAccountsList() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID a = fixtures().insertAccount(fx.workspaceId(), "A", "CHECKING", 12_345, false);
		fixtures().insertAccount(fx.workspaceId(), "B", "CASH", -300, false);
		fixtures().insertTransaction(fx.workspaceId(), a, fx.userId(), -2_345, false);

		String list = mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts")
				.cookie(session(fx.token()))).andReturn().getResponse().getContentAsString();
		long sum = java.util.regex.Pattern.compile("\"balanceCents\":(-?\\d+)").matcher(list).results()
				.mapToLong(m -> Long.parseLong(m.group(1))).sum();
		String body = report(fx).getResponse().getContentAsString();

		assertThat(body).contains("\"totalBalanceCents\":" + sum);
		assertThat(sum).isEqualTo(9_700);
	}

	@Test
	void nonMemberGetsNotFoundAndNoSessionGetsUnauthorized() throws Exception {
		Fixture mine = fixtures().workspaceWithOwner();
		Fixture stranger = fixtures().workspaceWithOwner();

		mvc.perform(get("/api/v1/workspaces/" + mine.workspaceId() + "/reports/balance")
				.cookie(session(stranger.token()))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/workspaces/" + mine.workspaceId() + "/reports/balance"))
				.andExpect(status().isUnauthorized());
	}

}
