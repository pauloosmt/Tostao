package com.tostao.tostao_api.recurrence;

import static com.tostao.tostao_api.recurrence.RecurrenceScenario.createRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.recurrencesFingerprint;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.recurrencesUrl;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.today;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.valid;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.with;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.PendingScenario.body;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsFingerprint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
import com.tostao.tostao_api.transaction.PendingScenario;

/**
 * História 5: só quem pode altera, e só no seu workspace. Acesso cruzado e papéis para cada uma das quatro rotas
 * (FR-021 a FR-023, SC-008, SC-009).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RecurrenceAccessTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	/** O banco é compartilhado: deixa de lado os lançamentos marcados e as recorrências que sobraram de outros testes. */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
		owner.client().sql("select auto_clear_due(date '2100-12-31')").query(Integer.class).single();
	}

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private record Mine(RecurrenceScenario s, String id) {
	}

	/** Uma recorrência com início 40 dias atrás (tem ocorrências futuras para encerrar). */
	private Mine mine() throws Exception {
		RecurrenceScenario s = RecurrenceScenario.create(fixtures());
		String json = createRecurrence(mvc, s.owner(), with(valid(s), "startOn", "\"" + today().minusDays(40) + "\""))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return new Mine(s, JsonPath.read(json, "$.id"));
	}

	/** As quatro operações, como a pessoa dada (nula = sem sessão), no workspace e na recorrência dados. */
	private ResultActions[] operations(UUID workspaceId, Fixture who, String id, RecurrenceScenario s) throws Exception {
		String base = recurrencesUrl(workspaceId);
		MockHttpServletRequestBuilder[] requests = {
				post(base).contentType(MediaType.APPLICATION_JSON).content(with(valid(s))),
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

		// "sem permissão" é distinto de "não encontrado"
		results[0].andExpect(status().isForbidden());
		results[1].andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
		results[2].andExpect(status().isOk()).andExpect(jsonPath("$.id").value(m.id()))
				.andExpect(jsonPath("$.occurrences.length()").isNumber());
		results[3].andExpect(status().isForbidden());
		assertThat(fingerprints(m.s().owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void aViewerCannotTurnTheMarkerOnInATransaction() throws Exception {
		Mine m = mine();
		Fixture viewer = fixtures().addMember(m.s().owner().workspaceId(), "VIEWER");
		String json = body("EXPENSE", m.s().checking(), 1_000, today().toString(), "x", m.s().rent(), "PENDING");
		String before = fingerprints(m.s().owner().workspaceId());

		PendingScenario.create(mvc, viewer, json.substring(0, json.length() - 1) + ",\"autoClear\":true}")
				.andExpect(status().isForbidden());

		assertThat(fingerprints(m.s().owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void editorsCreateAndEndEvenAnotherPersonsRecurrenceAndTheAuthorNeverChanges() throws Exception {
		Mine m = mine();
		Fixture editor = fixtures().addMember(m.s().owner().workspaceId(), "EDITOR");

		// o editor cria: a recorrência e as ocorrências são dele
		String json = createRecurrence(mvc, editor, with(valid(m.s()))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.createdBy").value(editor.userId().toString())).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(json, "$.occurrences[*].createdBy")).containsOnly(editor.userId().toString());

		// o editor encerra a recorrência do dono: o autor das ocorrências não muda
		mvc.perform(post(recurrencesUrl(m.s().owner().workspaceId()) + "/" + m.id() + "/end").cookie(session(editor.token())))
				.andExpect(status().isOk());
		assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = cast(:id as uuid) and created_by <> :u")
				.param("id", m.id()).param("u", m.s().owner().userId()).query(Long.class).single()).isZero();

		// o dono encerra a do editor
		mvc.perform(post(recurrencesUrl(m.s().owner().workspaceId()) + "/" + JsonPath.read(json, "$.id") + "/end")
				.cookie(session(m.s().owner().token()))).andExpect(status().isOk());
	}

	@Test
	void withoutASessionEveryOperationIsRefused() throws Exception {
		Mine m = mine();
		String before = fingerprints(m.s().owner().workspaceId());

		for (ResultActions result : operations(m.s().owner().workspaceId(), null, m.id(), m.s())) {
			result.andExpect(status().isUnauthorized());
		}
		assertThat(fingerprints(m.s().owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void aRecurrenceOfAnotherWorkspaceIsNeverReachedByItsId() throws Exception {
		Mine mine = mine();
		Mine theirs = mine();
		String theirBefore = fingerprints(theirs.s().owner().workspaceId());

		// o id do outro, nas rotas do meu workspace
		ResultActions[] results = operations(mine.s().owner().workspaceId(), mine.s().owner(), theirs.id(), mine.s());
		results[2].andExpect(status().isNotFound());
		results[3].andExpect(status().isNotFound());

		assertThat(fingerprints(theirs.s().owner().workspaceId())).isEqualTo(theirBefore);
		// a lista só tem as minhas
		assertThat(JsonPath.<List<String>>read(results[1].andReturn().getResponse().getContentAsString(), "$[*].id"))
				.doesNotContain(theirs.id());
	}

	@Test
	void anotherWorkspacesAccountAndCategoryCannotBeUsedToCreate() throws Exception {
		Mine mine = mine();
		Mine theirs = mine();
		String theirBefore = fingerprints(theirs.s().owner().workspaceId());
		String myBefore = fingerprints(mine.s().owner().workspaceId());

		createRecurrence(mvc, mine.s().owner(), with(valid(mine.s()), "accountId", "\"" + theirs.s().checking() + "\""))
				.andExpect(status().isNotFound());
		createRecurrence(mvc, mine.s().owner(), with(valid(mine.s()), "categoryId", "\"" + theirs.s().rent() + "\""))
				.andExpect(status().isNotFound());

		assertThat(fingerprints(theirs.s().owner().workspaceId())).isEqualTo(theirBefore);
		assertThat(fingerprints(mine.s().owner().workspaceId())).isEqualTo(myBefore);
	}

}
