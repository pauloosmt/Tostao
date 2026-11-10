package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchaseBody;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchasesUrl;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsFingerprint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
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

/**
 * História 4: só quem pode altera, e só no seu workspace. Acesso cruzado e papéis para cada uma das três rotas
 * (FR-015 a FR-017, SC-007, SC-008).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InstallmentAccessTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private record Notebook(InstallmentScenario s, UUID group, List<UUID> ids) {
	}

	/** Um plano de 3 parcelas de 100000 criadas pelo dono (a 1ª efetivada, as outras pendentes). */
	private Notebook notebook() {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID u = s.owner().userId();
		UUID group = UUID.randomUUID();
		List<UUID> ids = List.of(
				fixtures().insertInstallment(w, s.checking(), u, s.gadgets(), group, 1, 3, 100_000, LocalDate.of(2026, 11, 10),
						"Notebook (1/3)", "CLEARED", false),
				fixtures().insertInstallment(w, s.checking(), u, s.gadgets(), group, 2, 3, 100_000, LocalDate.of(2026, 12, 10),
						"Notebook (2/3)", "PENDING", false),
				fixtures().insertInstallment(w, s.checking(), u, s.gadgets(), group, 3, 3, 100_000, LocalDate.of(2027, 1, 10),
						"Notebook (3/3)", "PENDING", false));
		return new Notebook(s, group, ids);
	}

	/** As três operações da compra parcelada, como a pessoa dada (nula = sem sessão), no workspace e plano dados. */
	private ResultActions[] operations(UUID workspaceId, Fixture who, UUID group, InstallmentScenario s) throws Exception {
		String base = purchasesUrl(workspaceId);
		MockHttpServletRequestBuilder[] requests = {
				post(base).contentType(MediaType.APPLICATION_JSON)
						.content(purchaseBody(s.checking(), s.gadgets(), 30_000, 3, "2026-11-10", "Nova", null)),
				get(base + "/" + group),
				post(base + "/" + group + "/cancel") };
		ResultActions[] results = new ResultActions[requests.length];
		for (int i = 0; i < requests.length; i++) {
			results[i] = mvc.perform(who == null ? requests[i] : requests[i].cookie(session(who.token())));
		}
		return results;
	}

	@Test
	void aNonMemberGetsTheSameNotFoundAsAMissingWorkspaceAndNothingChanges() throws Exception {
		Notebook n = notebook();
		Fixture outsider = fixtures().workspaceWithOwner();
		String before = transactionsFingerprint(owner, n.s().owner().workspaceId());

		ResultActions[] real = operations(n.s().owner().workspaceId(), outsider, n.group(), n.s());
		ResultActions[] missing = operations(UUID.randomUUID(), outsider, n.group(), n.s());
		for (int i = 0; i < 3; i++) {
			real[i].andExpect(status().isNotFound());
			missing[i].andExpect(status().isNotFound());
			String a = real[i].andReturn().getResponse().getContentAsString();
			String b = missing[i].andReturn().getResponse().getContentAsString();
			assertThat((String) JsonPath.read(a, "$.title")).as("operação %d".formatted(i)).isEqualTo(JsonPath.read(b, "$.title"));
			assertThat((String) JsonPath.read(a, "$.detail")).as("operação %d".formatted(i)).isEqualTo(JsonPath.read(b, "$.detail"));
		}
		assertThat(transactionsFingerprint(owner, n.s().owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void aViewerReadsThePlanButCannotRegisterOrCancel() throws Exception {
		Notebook n = notebook();
		Fixture viewer = fixtures().addMember(n.s().owner().workspaceId(), "VIEWER");
		String before = transactionsFingerprint(owner, n.s().owner().workspaceId());

		ResultActions[] results = operations(n.s().owner().workspaceId(), viewer, n.group(), n.s());

		// "sem permissão" é distinto de "não encontrado"
		results[0].andExpect(status().isForbidden());
		results[1].andExpect(status().isOk()).andExpect(jsonPath("$.groupId").value(n.group().toString()))
				.andExpect(jsonPath("$.totalCents").value(300_000)).andExpect(jsonPath("$.installments.length()").value(3));
		results[2].andExpect(status().isForbidden());
		assertThat(transactionsFingerprint(owner, n.s().owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void editorsAndOwnersRegisterAndCancelEvenAnotherPersonsPlan() throws Exception {
		Notebook n = notebook();
		Fixture editor = fixtures().addMember(n.s().owner().workspaceId(), "EDITOR");

		// o editor registra: as parcelas são dele
		String json = mvc.perform(post(purchasesUrl(n.s().owner().workspaceId())).cookie(session(editor.token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(purchaseBody(n.s().checking(), n.s().gadgets(), 30_000, 3, "2026-11-10", "Do editor", null)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].createdBy")).containsOnly(editor.userId().toString());

		// o editor cancela o plano que o dono criou: o autor das parcelas não muda
		mvc.perform(post(purchasesUrl(n.s().owner().workspaceId()) + "/" + n.group() + "/cancel")
				.cookie(session(editor.token()))).andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(2));
		assertThat(owner.client().sql("select count(*) from transactions where installment_group_id = :g and created_by = :u")
				.param("g", n.group()).param("u", n.s().owner().userId()).query(Long.class).single()).isEqualTo(3L);

		// o dono cancela o plano do editor
		UUID editorGroup = UUID.fromString(JsonPath.read(json, "$.groupId"));
		mvc.perform(post(purchasesUrl(n.s().owner().workspaceId()) + "/" + editorGroup + "/cancel")
				.cookie(session(n.s().owner().token()))).andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(2));
	}

	@Test
	void withoutASessionEveryOperationIsRefused() throws Exception {
		Notebook n = notebook();
		String before = transactionsFingerprint(owner, n.s().owner().workspaceId());

		for (ResultActions result : operations(n.s().owner().workspaceId(), null, n.group(), n.s())) {
			result.andExpect(status().isUnauthorized());
		}
		assertThat(transactionsFingerprint(owner, n.s().owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void aPlanOfAnotherWorkspaceIsNeverReachedByItsGroup() throws Exception {
		Notebook mine = notebook();
		Notebook theirs = notebook();
		String theirBefore = transactionsFingerprint(owner, theirs.s().owner().workspaceId());
		String myBefore = transactionsFingerprint(owner, mine.s().owner().workspaceId());

		// o grupo do outro, nas rotas do meu workspace
		ResultActions[] results = operations(mine.s().owner().workspaceId(), mine.s().owner(), theirs.group(), mine.s());
		results[1].andExpect(status().isNotFound());
		results[2].andExpect(status().isNotFound());

		// só o registro (que não usa o grupo) criou algo, e no meu workspace
		results[0].andExpect(status().isCreated());
		assertThat(transactionsFingerprint(owner, theirs.s().owner().workspaceId())).isEqualTo(theirBefore);
		assertThat(transactionsFingerprint(owner, mine.s().owner().workspaceId())).isNotEqualTo(myBefore);
		assertThat(owner.client().sql("select count(*) from transactions where installment_group_id = :g and deleted_at is null")
				.param("g", theirs.group()).query(Long.class).single()).isEqualTo(3L);
	}

	@Test
	void anotherWorkspacesAccountAndCategoryCannotBeUsedToRegister() throws Exception {
		Notebook mine = notebook();
		Notebook theirs = notebook();
		String theirBefore = transactionsFingerprint(owner, theirs.s().owner().workspaceId());
		String myBefore = transactionsFingerprint(owner, mine.s().owner().workspaceId());

		mvc.perform(post(purchasesUrl(mine.s().owner().workspaceId())).cookie(session(mine.s().owner().token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(purchaseBody(theirs.s().checking(), mine.s().gadgets(), 30_000, 3, "2026-11-10", "x", null)))
				.andExpect(status().isNotFound());
		mvc.perform(post(purchasesUrl(mine.s().owner().workspaceId())).cookie(session(mine.s().owner().token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(purchaseBody(mine.s().checking(), theirs.s().gadgets(), 30_000, 3, "2026-11-10", "x", null)))
				.andExpect(status().isNotFound());

		assertThat(transactionsFingerprint(owner, theirs.s().owner().workspaceId())).isEqualTo(theirBefore);
		assertThat(transactionsFingerprint(owner, mine.s().owner().workspaceId())).isEqualTo(myBefore);
	}

}
