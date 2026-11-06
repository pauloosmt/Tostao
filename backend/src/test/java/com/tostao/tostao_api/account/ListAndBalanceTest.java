package com.tostao.tostao_api.account;

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

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** Saldo = saldo inicial + lançamentos não excluídos da conta. Lançamentos semeados pelo dono do banco. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ListAndBalanceTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private long balanceOf(Fixture fx, UUID accountId) throws Exception {
		String body = mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts/" + accountId)
				.cookie(session(fx.token()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return Long.parseLong(body.replaceAll(".*\"balanceCents\":(-?\\d+).*", "$1"));
	}

	@Test
	void withoutTransactionsTheBalanceIsTheInitialBalance() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 100_000, false);

		assertThat(balanceOf(fx, account)).isEqualTo(100_000);
	}

	@Test
	void inflowsAndOutflowsMoveTheBalanceByTheirSum() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 100_000, false);
		fixtures().insertTransaction(fx.workspaceId(), account, fx.userId(), 25_000, false);
		fixtures().insertTransaction(fx.workspaceId(), account, fx.userId(), -10_000, false);

		assertThat(balanceOf(fx, account)).isEqualTo(115_000);
	}

	@Test
	void logicallyDeletedTransactionsDoNotCount() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 100_000, false);
		fixtures().insertTransaction(fx.workspaceId(), account, fx.userId(), 25_000, false);
		fixtures().insertTransaction(fx.workspaceId(), account, fx.userId(), 99_999, true);

		assertThat(balanceOf(fx, account)).isEqualTo(125_000);
	}

	@Test
	void transactionsOfAnotherAccountDoNotCount() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID mine = fixtures().insertAccount(fx.workspaceId(), "Minha", "CHECKING", 1_000, false);
		UUID other = fixtures().insertAccount(fx.workspaceId(), "Outra", "CHECKING", 0, false);
		fixtures().insertTransaction(fx.workspaceId(), other, fx.userId(), 5_000, false);

		assertThat(balanceOf(fx, mine)).isEqualTo(1_000);
		assertThat(balanceOf(fx, other)).isEqualTo(5_000);
	}

	@Test
	void aTransferAffectsEachAccountByItsOwnLeg() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID from = fixtures().insertAccount(fx.workspaceId(), "Origem", "CHECKING", 100_000, false);
		UUID to = fixtures().insertAccount(fx.workspaceId(), "Destino", "SAVINGS", 0, false);
		fixtures().insertTransfer(fx.workspaceId(), from, to, fx.userId(), 30_000);

		assertThat(balanceOf(fx, from)).isEqualTo(70_000);
		assertThat(balanceOf(fx, to)).isEqualTo(30_000);
	}

	@Test
	void centsAddUpExactly() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Centavos", "CASH", 0, false);
		fixtures().insertTransaction(fx.workspaceId(), account, fx.userId(), 10, false);
		fixtures().insertTransaction(fx.workspaceId(), account, fx.userId(), 20, false);

		assertThat(balanceOf(fx, account)).isEqualTo(30);
	}

	@Test
	void listAndGetAgreeOnTheBalance() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 100_000, false);
		fixtures().insertTransaction(fx.workspaceId(), account, fx.userId(), -2_500, false);

		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts").cookie(session(fx.token())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].balanceCents").value(97_500));
		assertThat(balanceOf(fx, account)).isEqualTo(97_500);
	}

	@Test
	void archivedAccountsAreHiddenUnlessAsked() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		fixtures().insertAccount(fx.workspaceId(), "Ativa", "CHECKING", 0, false);
		fixtures().insertAccount(fx.workspaceId(), "Velha", "CHECKING", 0, true);

		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts").cookie(session(fx.token())))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("Ativa"));
		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts?includeArchived=true")
				.cookie(session(fx.token())))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[1].name").value("Velha"))
				.andExpect(jsonPath("$[1].archived").value(true));
	}

	@Test
	void anAccountOfAnotherWorkspaceIsNotFoundOnMyRoute() throws Exception {
		Fixture mine = fixtures().workspaceWithOwner();
		Fixture theirs = fixtures().workspaceWithOwner();
		UUID foreign = fixtures().insertAccount(theirs.workspaceId(), "Alheia", "CHECKING", 1_000, false);

		mvc.perform(get("/api/v1/workspaces/" + mine.workspaceId() + "/accounts/" + foreign)
				.cookie(session(mine.token()))).andExpect(status().isNotFound());
	}

	@Test
	void unknownAndMalformedAccountIdsAreNotFoundNeverServerErrors() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts/" + UUID.randomUUID())
				.cookie(session(fx.token()))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts/abc").cookie(session(fx.token())))
				.andExpect(status().isNotFound());
	}

}
