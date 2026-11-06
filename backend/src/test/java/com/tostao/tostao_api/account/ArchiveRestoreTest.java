package com.tostao.tostao_api.account;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class ArchiveRestoreTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private String base(UUID workspaceId) {
		return "/api/v1/workspaces/" + workspaceId + "/accounts";
	}

	private MvcResult archive(Fixture fx, UUID workspaceId, UUID accountId) throws Exception {
		return mvc.perform(post(base(workspaceId) + "/" + accountId + "/archive").cookie(session(fx.token())))
				.andReturn();
	}

	private MvcResult restore(Fixture fx, UUID workspaceId, UUID accountId) throws Exception {
		return mvc.perform(post(base(workspaceId) + "/" + accountId + "/restore").cookie(session(fx.token())))
				.andReturn();
	}

	private boolean archivedInDb(UUID accountId) {
		return owner.client().sql("select archived from accounts where id = :id").param("id", accountId)
				.query(Boolean.class).single();
	}

	@Test
	void archivingHidesTheAccountFromTheListAndTheTotalButKeepsEverything() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 10_000, false);
		fixtures().insertAccount(fx.workspaceId(), "Outra", "CHECKING", 5_000, false);
		fixtures().insertTransaction(fx.workspaceId(), account, fx.userId(), 2_000, false);

		MvcResult result = archive(fx, fx.workspaceId(), account);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString()).contains("\"archived\":true");
		mvc.perform(get(base(fx.workspaceId())).cookie(session(fx.token())))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].name").value("Outra"));
		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/reports/balance").cookie(session(fx.token())))
				.andExpect(jsonPath("$.totalBalanceCents").value(5_000));
		long transactions = owner.client().sql("select count(*) from transactions where account_id = :a")
				.param("a", account).query(Long.class).single();
		assertThat(transactions).isEqualTo(1);
		assertThat(archivedInDb(account)).isTrue();
	}

	@Test
	void archivedAccountsAppearWhenAskedForAndKeepTheirBalance() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 10_000, false);
		fixtures().insertTransaction(fx.workspaceId(), account, fx.userId(), 2_000, false);
		archive(fx, fx.workspaceId(), account);

		mvc.perform(get(base(fx.workspaceId()) + "?includeArchived=true").cookie(session(fx.token())))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].archived").value(true))
				.andExpect(jsonPath("$[0].balanceCents").value(12_000));
	}

	@Test
	void archivingAnAlreadyArchivedAccountSucceedsWithoutChangingIt() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		String first = archive(fx, fx.workspaceId(), account).getResponse().getContentAsString();

		MvcResult again = archive(fx, fx.workspaceId(), account);

		assertThat(again.getResponse().getStatus()).isEqualTo(200);
		assertThat(again.getResponse().getContentAsString()).isEqualTo(first);
	}

	@Test
	void restoringBringsTheAccountBackToTheListAndTheTotal() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 7_000, true);

		MvcResult result = restore(fx, fx.workspaceId(), account);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString()).contains("\"archived\":false");
		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/reports/balance").cookie(session(fx.token())))
				.andExpect(jsonPath("$.totalBalanceCents").value(7_000));
	}

	@Test
	void restoringAnActiveAccountSucceedsWithoutEffect() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		String before = mvc.perform(get(base(fx.workspaceId()) + "/" + account).cookie(session(fx.token())))
				.andReturn().getResponse().getContentAsString();

		MvcResult result = restore(fx, fx.workspaceId(), account);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString()).isEqualTo(before);
	}

	@Test
	void restoringIsRefusedWhenAnActiveAccountUsesTheName() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID archived = fixtures().insertAccount(fx.workspaceId(), "Nubank", "CHECKING", 0, true);
		fixtures().insertAccount(fx.workspaceId(), "nubank", "CHECKING", 0, false);

		MvcResult result = restore(fx, fx.workspaceId(), archived);

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(archivedInDb(archived)).isTrue();
	}

	@Test
	void anAccountWithABalanceCanBeArchivedAndLeavesTheTotal() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID withMoney = fixtures().insertAccount(fx.workspaceId(), "Com saldo", "SAVINGS", 999_900, false);

		assertThat(archive(fx, fx.workspaceId(), withMoney).getResponse().getStatus()).isEqualTo(200);

		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/reports/balance").cookie(session(fx.token())))
				.andExpect(jsonPath("$.totalBalanceCents").value(0));
	}

	@Test
	void thereIsNoWayToDeleteAnAccount() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Permanente", "CHECKING", 0, false);

		MvcResult result = mvc.perform(delete(base(fx.workspaceId()) + "/" + account).cookie(session(fx.token())))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isIn(404, 405);
		long remaining = owner.client().sql("select count(*) from accounts where id = :id").param("id", account)
				.query(Long.class).single();
		assertThat(remaining).isEqualTo(1);
	}

	@Test
	void permissionsAndIsolationApplyToArchiveAndRestore() throws Exception {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture viewer = fixtures().addMember(ownerFx.workspaceId(), "VIEWER");
		Fixture editor = fixtures().addMember(ownerFx.workspaceId(), "EDITOR");
		Fixture stranger = fixtures().workspaceWithOwner();
		UUID active = fixtures().insertAccount(ownerFx.workspaceId(), "Ativa", "CHECKING", 0, false);
		UUID archived = fixtures().insertAccount(ownerFx.workspaceId(), "Arquivada", "CHECKING", 0, true);
		UUID foreign = fixtures().insertAccount(stranger.workspaceId(), "Alheia", "CHECKING", 0, false);

		assertThat(archive(viewer, ownerFx.workspaceId(), active).getResponse().getStatus()).isEqualTo(403);
		assertThat(restore(viewer, ownerFx.workspaceId(), archived).getResponse().getStatus()).isEqualTo(403);
		assertThat(archivedInDb(active)).isFalse();
		assertThat(archivedInDb(archived)).isTrue();

		assertThat(archive(stranger, ownerFx.workspaceId(), active).getResponse().getStatus()).isEqualTo(404);
		assertThat(restore(stranger, ownerFx.workspaceId(), archived).getResponse().getStatus()).isEqualTo(404);
		assertThat(archive(ownerFx, ownerFx.workspaceId(), foreign).getResponse().getStatus()).isEqualTo(404);
		assertThat(restore(ownerFx, ownerFx.workspaceId(), foreign).getResponse().getStatus()).isEqualTo(404);
		assertThat(archivedInDb(foreign)).isFalse();

		assertThat(archive(editor, ownerFx.workspaceId(), active).getResponse().getStatus()).isEqualTo(200);
		assertThat(restore(ownerFx, ownerFx.workspaceId(), archived).getResponse().getStatus()).isEqualTo(200);
		mvc.perform(post(base(ownerFx.workspaceId()) + "/" + active + "/archive")).andExpect(status().isUnauthorized());
	}

}
