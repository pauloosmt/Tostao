package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.PendingScenario.body;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** História 3: só OWNER e EDITOR mudam o estado, VIEWER só lê, e cada workspace só vê o seu. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PendingAccessTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private UUID pendingRent(PendingScenario s) {
		return fixtures().insertLedgerTransaction(s.owner().workspaceId(), s.checking(), s.owner().userId(), s.food(),
				"EXPENSE", 80_000, java.time.LocalDate.of(2026, 11, 20), "Aluguel", false, "PENDING");
	}

	/** As três operações que mudam o estado, como a pessoa dada (nula = sem sessão), no workspace e lançamento dados. */
	private ResultActions[] stateChanges(UUID workspaceId, Fixture who, UUID transactionId, PendingScenario s)
			throws Exception {
		String base = transactionsUrl(workspaceId);
		MockHttpServletRequestBuilder[] requests = {
				post(base + "/" + transactionId + "/clear"),
				post(base).contentType(MediaType.APPLICATION_JSON)
						.content(body("EXPENSE", s.checking(), 100, "2026-11-10", "Nova", s.food(), "PENDING")),
				put(base + "/" + transactionId).contentType(MediaType.APPLICATION_JSON).content(
						"{\"accountId\":\"%s\",\"amountCents\":80000,\"occurredOn\":\"2026-11-20\",\"description\":\"Aluguel\",\"categoryId\":\"%s\",\"status\":\"CLEARED\"}"
								.formatted(s.checking(), s.food())) };
		ResultActions[] results = new ResultActions[requests.length];
		for (int i = 0; i < requests.length; i++) {
			results[i] = mvc.perform(who == null ? requests[i] : requests[i].cookie(session(who.token())));
		}
		return results;
	}

	@Test
	void aNonMemberGetsTheSameNotFoundAsAMissingWorkspaceAndNothingChanges() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingRent(s);
		Fixture outsider = fixtures().workspaceWithOwner();
		String before = PendingScenario.transactionsFingerprint(owner, s.owner().workspaceId());

		ResultActions[] real = stateChanges(s.owner().workspaceId(), outsider, id, s);
		ResultActions[] missing = stateChanges(UUID.randomUUID(), outsider, id, s);
		for (int i = 0; i < 3; i++) {
			real[i].andExpect(status().isNotFound());
			missing[i].andExpect(status().isNotFound());
			String a = real[i].andReturn().getResponse().getContentAsString();
			String b = missing[i].andReturn().getResponse().getContentAsString();
			assertThat((String) JsonPath.read(a, "$.title")).as("operação %d".formatted(i)).isEqualTo(JsonPath.read(b, "$.title"));
			assertThat((String) JsonPath.read(a, "$.detail")).as("operação %d".formatted(i)).isEqualTo(JsonPath.read(b, "$.detail"));
		}
		assertThat(PendingScenario.transactionsFingerprint(owner, s.owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void aViewerReadsTheStatusButCannotChangeIt() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingRent(s);
		Fixture viewer = fixtures().addMember(s.owner().workspaceId(), "VIEWER");
		String before = PendingScenario.transactionsFingerprint(owner, s.owner().workspaceId());

		PendingScenario.list(mvc, viewer, "status=PENDING").andExpect(status().isOk())
				.andExpect(jsonPath("$.totalItems").value(1)).andExpect(jsonPath("$.items[0].status").value("PENDING"));
		for (ResultActions result : stateChanges(s.owner().workspaceId(), viewer, id, s)) {
			// "sem permissão" é distinto de "não encontrado"
			result.andExpect(status().isForbidden());
		}
		assertThat(PendingScenario.transactionsFingerprint(owner, s.owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void editorsAndOwnersChangeTheStatusEvenOnAnotherPersonsTransaction() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		Fixture editor = fixtures().addMember(s.owner().workspaceId(), "EDITOR");
		UUID id = pendingRent(s);

		PendingScenario.clear(mvc, editor, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLEARED"))
				.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString()));
		PendingScenario.edit(mvc, s.owner(), id, "{\"accountId\":\"%s\",\"amountCents\":80000,\"occurredOn\":\"2026-11-20\",\"description\":\"Aluguel\",\"categoryId\":\"%s\",\"status\":\"PENDING\"}"
				.formatted(s.checking(), s.food())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
		PendingScenario.create(mvc, editor, body("INCOME", s.checking(), 100, "2026-11-10", "Do editor", s.salary(), "PENDING"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.createdBy").value(editor.userId().toString()));
	}

	@Test
	void withoutASessionEveryStateChangeIsRefused() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingRent(s);

		for (ResultActions result : stateChanges(s.owner().workspaceId(), null, id, s)) {
			result.andExpect(status().isUnauthorized());
		}
		assertThat(PendingScenario.statusOf(owner, id)).isEqualTo("PENDING");
	}

	@Test
	void aTransactionOfAnotherWorkspaceIsNeverReachedByItsId() throws Exception {
		PendingScenario a = PendingScenario.create(fixtures());
		PendingScenario b = PendingScenario.create(fixtures());
		UUID foreign = pendingRent(b);

		// o id do B, nas rotas do workspace do A
		PendingScenario.clear(mvc, a.owner(), foreign).andExpect(status().isNotFound());
		PendingScenario.edit(mvc, a.owner(), foreign, "{\"accountId\":\"%s\",\"amountCents\":1,\"occurredOn\":\"2026-11-20\",\"description\":\"x\",\"categoryId\":\"%s\",\"status\":\"CLEARED\"}"
				.formatted(a.checking(), a.food())).andExpect(status().isNotFound());
		assertThat(PendingScenario.statusOf(owner, foreign)).isEqualTo("PENDING");
	}

	@Test
	void anotherWorkspacesAccountCannotBeUsedToCreateAPendingTransaction() throws Exception {
		PendingScenario a = PendingScenario.create(fixtures());
		PendingScenario b = PendingScenario.create(fixtures());
		long before = owner.client().sql("select count(*) from transactions where workspace_id = :w")
				.param("w", b.owner().workspaceId()).query(Long.class).single();

		PendingScenario.create(mvc, a.owner(), body("EXPENSE", b.checking(), 100, "2026-11-10", "x", a.food(), "PENDING"))
				.andExpect(status().isNotFound());
		assertThat(owner.client().sql("select count(*) from transactions where workspace_id = :w")
				.param("w", b.owner().workspaceId()).query(Long.class).single()).isEqualTo(before);
	}

}
