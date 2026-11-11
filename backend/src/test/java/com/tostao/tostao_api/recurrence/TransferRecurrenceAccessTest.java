package com.tostao.tostao_api.recurrence;

import static com.tostao.tostao_api.recurrence.RecurrenceScenario.createRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.recurrencesFingerprint;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.recurrencesUrl;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.today;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.with;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsFingerprint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * História 4: só quem pode altera, e só no seu workspace (FR-013, SC-007). Os resultados são os da recorrência da spec 011,
 * agora para a transferência: papéis, acesso cruzado e identificadores malformados.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TransferRecurrenceAccessTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	/** O banco é compartilhado: deixa de lado as recorrências e os lançamentos marcados de outros testes. */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
		owner.client().sql("select auto_clear_due(date '2100-12-31')").query(Integer.class).single();
	}

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private static Map<String, String> transfer(RecurrenceScenario s) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("accountId", "\"" + s.checking() + "\"");
		fields.put("destinationAccountId", "\"" + s.savings() + "\"");
		fields.put("type", "\"TRANSFER\"");
		fields.put("amountCents", "50000");
		fields.put("description", "\"Reserva\"");
		fields.put("frequency", "\"MONTHLY\"");
		fields.put("startOn", "\"" + today().minusDays(40) + "\"");
		return fields;
	}

	private record Mine(RecurrenceScenario s, String id) {
	}

	private Mine mine() throws Exception {
		RecurrenceScenario s = RecurrenceScenario.create(fixtures());
		String json = createRecurrence(mvc, s.owner(), with(transfer(s))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return new Mine(s, JsonPath.read(json, "$.id"));
	}

	/** As quatro operações, como a pessoa dada (nula = sem sessão), no workspace e na recorrência dados. */
	private ResultActions[] operations(UUID workspaceId, Fixture who, String id, RecurrenceScenario s) throws Exception {
		String base = recurrencesUrl(workspaceId);
		MockHttpServletRequestBuilder[] requests = {
				post(base).contentType(MediaType.APPLICATION_JSON).content(with(transfer(s))),
				get(base),
				get(base + "/" + id),
				post(base + "/" + id + "/end") };
		ResultActions[] results = new ResultActions[requests.length];
		for (int i = 0; i < requests.length; i++) {
			results[i] = mvc.perform(who == null ? requests[i] : requests[i].cookie(session(who.token())));
		}
		return results;
	}

	private String fingerprints(UUID workspaceId) {
		return transactionsFingerprint(owner, workspaceId) + "|" + recurrencesFingerprint(owner, workspaceId);
	}

	@Test
	void aNonMemberGetsTheSameNotFoundAsAMissingWorkspaceAndNothingChanges() throws Exception {
		Mine m = mine();
		Fixture outsider = fixtures().workspaceWithOwner();
		String before = fingerprints(m.s().owner().workspaceId());

		ResultActions[] real = operations(m.s().owner().workspaceId(), outsider, m.id(), m.s());
		ResultActions[] missing = operations(UUID.randomUUID(), outsider, m.id(), m.s());
		for (int i = 0; i < 4; i++) {
			real[i].andExpect(status().isNotFound());
			missing[i].andExpect(status().isNotFound());
			String a = real[i].andReturn().getResponse().getContentAsString();
			String b = missing[i].andReturn().getResponse().getContentAsString();
			assertThat((String) JsonPath.read(a, "$.title")).as("operação %d".formatted(i)).isEqualTo(JsonPath.read(b, "$.title"));
			assertThat((String) JsonPath.read(a, "$.detail")).as("operação %d".formatted(i)).isEqualTo(JsonPath.read(b, "$.detail"));
		}
		assertThat(fingerprints(m.s().owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void aViewerReadsEverythingButCannotCreateOrEnd() throws Exception {
		Mine m = mine();
		Fixture viewer = fixtures().addMember(m.s().owner().workspaceId(), "VIEWER");
		String before = fingerprints(m.s().owner().workspaceId());

		ResultActions[] results = operations(m.s().owner().workspaceId(), viewer, m.id(), m.s());

		results[0].andExpect(status().isForbidden());
		results[1].andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].type").value("TRANSFER"));
		results[2].andExpect(status().isOk()).andExpect(jsonPath("$.id").value(m.id()))
				.andExpect(jsonPath("$.destinationAccountId").value(m.s().savings().toString()));
		results[3].andExpect(status().isForbidden());
		assertThat(fingerprints(m.s().owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void anEditorCreatesAndEndsLikeTheOwner() throws Exception {
		Mine m = mine();
		Fixture editor = fixtures().addMember(m.s().owner().workspaceId(), "EDITOR");

		createRecurrence(mvc, editor, with(transfer(m.s()))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.createdBy").value(editor.userId().toString()));
		mvc.perform(post(recurrencesUrl(editor.workspaceId()) + "/" + m.id() + "/end").cookie(session(editor.token())))
				.andExpect(status().isOk());
	}

	@Test
	void withoutASessionEveryOperationIs401() throws Exception {
		Mine m = mine();
		String before = fingerprints(m.s().owner().workspaceId());

		for (ResultActions result : operations(m.s().owner().workspaceId(), null, m.id(), m.s())) {
			result.andExpect(status().isUnauthorized());
		}
		assertThat(fingerprints(m.s().owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void theRecurrenceOfAnotherWorkspaceIsNotFoundByItsIdAndNothingChanges() throws Exception {
		Mine mine = mine();
		Mine theirs = mine();
		String theirBefore = fingerprints(theirs.s().owner().workspaceId());

		// a recorrência deles na rota do meu workspace
		ResultActions[] results = operations(mine.s().owner().workspaceId(), mine.s().owner(), theirs.id(), mine.s());

		results[2].andExpect(status().isNotFound());
		results[3].andExpect(status().isNotFound());
		// a lista de cada um só traz a própria
		results[1].andExpect(status().isOk()).andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(theirs.id()))));
		assertThat(fingerprints(theirs.s().owner().workspaceId())).isEqualTo(theirBefore);
	}

	@Test
	void theOwnerOfOneWorkspaceIsNotAMemberOfAnotherAndCannotUseItsAccounts() throws Exception {
		Mine mine = mine();
		RecurrenceScenario other = RecurrenceScenario.create(fixtures());
		String before = fingerprints(mine.s().owner().workspaceId());

		// as contas do outro workspace numa criação no meu: não encontradas
		createRecurrence(mvc, mine.s().owner(), with(transfer(other))).andExpect(status().isNotFound());
		// uma conta de cada: a de fora faz a criação inteira falhar
		createRecurrence(mvc, mine.s().owner(), with(transfer(mine.s()), "destinationAccountId", "\"" + other.savings() + "\"")).andExpect(status().isNotFound());
		assertThat(fingerprints(mine.s().owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void malformedIdentifiersAreNotFound() throws Exception {
		Mine m = mine();

		mvc.perform(get(recurrencesUrl(m.s().owner().workspaceId()) + "/abc").cookie(session(m.s().owner().token()))).andExpect(status().isNotFound());
		mvc.perform(post(recurrencesUrl(m.s().owner().workspaceId()) + "/abc/end").cookie(session(m.s().owner().token()))).andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/workspaces/abc/recurrences").cookie(session(m.s().owner().token())).contentType(MediaType.APPLICATION_JSON)
				.content(with(transfer(m.s())))).andExpect(status().isNotFound());
	}

}
