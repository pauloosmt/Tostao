package com.tostao.tostao_api.account;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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
class UpdateAccountTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private MvcResult update(Fixture fx, UUID workspaceId, UUID accountId, String json) throws Exception {
		return mvc.perform(put("/api/v1/workspaces/" + workspaceId + "/accounts/" + accountId)
				.cookie(session(fx.token())).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
	}

	private MvcResult update(Fixture fx, UUID accountId, String json) throws Exception {
		return update(fx, fx.workspaceId(), accountId, json);
	}

	private static String body(String name, long initialCents) {
		return "{\"name\":\"%s\",\"initialBalanceCents\":%d}".formatted(name, initialCents);
	}

	private String nameInDb(UUID accountId) {
		return owner.client().sql("select name from accounts where id = :id").param("id", accountId)
				.query(String.class).single();
	}

	private void assertInvalid(Fixture fx, UUID accountId, String json, String field) throws Exception {
		MvcResult result = update(fx, accountId, json);
		assertThat(result.getResponse().getStatus()).as(json).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).as(json).contains("\"field\":\"" + field + "\"");
	}

	@Test
	void renamingShowsTheNewNameInTheList() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Antigo", "CHECKING", 1_000, false);

		MvcResult result = update(fx, account, body("Novo nome", 1_000));

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts").cookie(session(fx.token())))
				.andExpect(jsonPath("$[0].name").value("Novo nome"));
	}

	@Test
	void changingTheInitialBalanceMovesTheBalanceByTheSameDelta() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 100_000, false);
		fixtures().insertTransaction(fx.workspaceId(), account, fx.userId(), -20_000, false);

		MvcResult result = update(fx, account, body("Corrente", 150_000));

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString()).contains("\"initialBalanceCents\":150000")
				.contains("\"balanceCents\":130000");
	}

	@Test
	void creditCardLimitAndDaysCanBeChanged() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID card = fixtures().insertAccount(fx.workspaceId(), "Cartão", "CREDIT_CARD", 0, false);

		MvcResult result = update(fx, card,
				"{\"name\":\"Cartão\",\"initialBalanceCents\":0,\"creditLimitCents\":800000,\"closingDay\":3,\"dueDay\":10}");

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString()).contains("\"creditLimitCents\":800000")
				.contains("\"closingDay\":3").contains("\"dueDay\":10");
	}

	@Test
	void theTypeIsImmutable() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);

		assertInvalid(fx, account, "{\"name\":\"Corrente\",\"type\":\"SAVINGS\",\"initialBalanceCents\":0}", "type");
		assertThat(update(fx, account, "{\"name\":\"Corrente\",\"type\":\"CHECKING\",\"initialBalanceCents\":0}")
				.getResponse().getStatus()).isEqualTo(200);
		String stored = owner.client().sql("select type from accounts where id = :id").param("id", account)
				.query(String.class).single();
		assertThat(stored).isEqualTo("CHECKING");
	}

	@Test
	void theSameValidationsAsCreationApply() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		UUID card = fixtures().insertAccount(fx.workspaceId(), "Cartão", "CREDIT_CARD", 0, false);

		assertInvalid(fx, account, body("", 0), "name");
		assertInvalid(fx, account, body("n".repeat(101), 0), "name");
		assertInvalid(fx, account, body("Ok", 1_000_000_000_001L), "initialBalanceCents");
		assertInvalid(fx, account, "{\"name\":\"Ok\"}", "initialBalanceCents");
		assertInvalid(fx, account, "{\"name\":\"Ok\",\"initialBalanceCents\":0,\"creditLimitCents\":10}",
				"creditLimitCents");
		assertInvalid(fx, account, "{\"name\":\"Ok\",\"initialBalanceCents\":0,\"closingDay\":5}", "closingDay");
		assertInvalid(fx, card, "{\"name\":\"Cartão\",\"initialBalanceCents\":0,\"dueDay\":0}", "dueDay");
		assertInvalid(fx, card, "{\"name\":\"Cartão\",\"initialBalanceCents\":0,\"creditLimitCents\":-1}",
				"creditLimitCents");
		for (String bad : new String[] {
				"{\"name\":\"Ok\",\"initialBalanceCents\":10.5}",
				"{\"name\":\"Ok\",\"initialBalanceCents\":0,\"archived\":true}",
				"{\"name\":\"Ok\",\"initialBalanceCents\":0,\"balanceCents\":1}",
				"{\"name\":\"Ok\",\"initialBalanceCents\":0,\"workspaceId\":\"" + UUID.randomUUID() + "\"}" }) {
			assertThat(update(fx, account, bad).getResponse().getStatus()).as(bad).isEqualTo(400);
		}
		assertThat(nameInDb(account)).isEqualTo("Corrente");
	}

	@Test
	void aNameUsedByAnotherActiveAccountIsRefused() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		fixtures().insertAccount(fx.workspaceId(), "Nubank", "CHECKING", 0, false);
		UUID other = fixtures().insertAccount(fx.workspaceId(), "Itaú", "CHECKING", 0, false);

		MvcResult result = update(fx, other, body("nubank", 0));

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(nameInDb(other)).isEqualTo("Itaú");
	}

	@Test
	void renamingToItsOwnNameWithDifferentCaseIsAccepted() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "nubank", "CHECKING", 0, false);

		assertThat(update(fx, account, body("Nubank", 0)).getResponse().getStatus()).isEqualTo(200);
		assertThat(nameInDb(account)).isEqualTo("Nubank");
	}

	@Test
	void anArchivedAccountCannotBeEdited() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID archived = fixtures().insertAccount(fx.workspaceId(), "Velha", "CHECKING", 0, true);

		MvcResult result = update(fx, archived, body("Tentativa", 5));

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(nameInDb(archived)).isEqualTo("Velha");
	}

	@Test
	void updatedAtAdvancesAndCreatedAtStays() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		String before = mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts/" + account)
				.cookie(session(fx.token()))).andReturn().getResponse().getContentAsString();

		String after = update(fx, account, body("Corrente 2", 0)).getResponse().getContentAsString();

		Instant createdBefore = Instant.parse(before.replaceAll(".*\"createdAt\":\"([^\"]+)\".*", "$1"));
		Instant createdAfter = Instant.parse(after.replaceAll(".*\"createdAt\":\"([^\"]+)\".*", "$1"));
		Instant updatedBefore = Instant.parse(before.replaceAll(".*\"updatedAt\":\"([^\"]+)\".*", "$1"));
		Instant updatedAfter = Instant.parse(after.replaceAll(".*\"updatedAt\":\"([^\"]+)\".*", "$1"));
		assertThat(createdAfter).isEqualTo(createdBefore);
		assertThat(updatedAfter).isAfter(updatedBefore);
	}

	@Test
	void concurrentRenamesOfDifferentAccountsToTheSameNameYieldOneSuccessAndNoServerError() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID a = fixtures().insertAccount(fx.workspaceId(), "Conta A", "CHECKING", 0, false);
		UUID b = fixtures().insertAccount(fx.workspaceId(), "Conta B", "CHECKING", 0, false);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Callable<Integer>> calls = new ArrayList<>();
			for (UUID id : new UUID[] { a, b }) {
				calls.add(() -> update(fx, id, body("Igual", 0)).getResponse().getStatus());
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(calls)) {
				statuses.add(f.get());
			}
			assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		}
		finally {
			pool.shutdownNow();
		}
		long named = owner.client().sql("select count(*) from accounts where workspace_id = :w and name = 'Igual'")
				.param("w", fx.workspaceId()).query(Long.class).single();
		assertThat(named).isEqualTo(1);
	}

	@Test
	void permissionsAndIsolationApplyToEditing() throws Exception {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture viewer = fixtures().addMember(ownerFx.workspaceId(), "VIEWER");
		Fixture editor = fixtures().addMember(ownerFx.workspaceId(), "EDITOR");
		Fixture stranger = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(ownerFx.workspaceId(), "Original", "CHECKING", 0, false);
		UUID foreign = fixtures().insertAccount(stranger.workspaceId(), "Alheia", "CHECKING", 0, false);

		assertThat(update(viewer, account, body("Viewer", 0)).getResponse().getStatus()).isEqualTo(403);
		assertThat(nameInDb(account)).isEqualTo("Original");
		assertThat(update(stranger, ownerFx.workspaceId(), account, body("Estranho", 0)).getResponse().getStatus())
				.isEqualTo(404);
		assertThat(nameInDb(account)).isEqualTo("Original");
		assertThat(update(ownerFx, ownerFx.workspaceId(), foreign, body("Invasão", 0)).getResponse().getStatus())
				.isEqualTo(404);
		assertThat(nameInDb(foreign)).isEqualTo("Alheia");
		assertThat(update(editor, account, body("Editor", 0)).getResponse().getStatus()).isEqualTo(200);
		assertThat(update(ownerFx, account, body("Dono", 0)).getResponse().getStatus()).isEqualTo(200);
		mvc.perform(put("/api/v1/workspaces/" + ownerFx.workspaceId() + "/accounts/" + account)
				.contentType(MediaType.APPLICATION_JSON).content(body("Anônimo", 0)))
				.andExpect(status().isUnauthorized());
	}

}
