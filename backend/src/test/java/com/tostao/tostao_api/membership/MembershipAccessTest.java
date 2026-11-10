package com.tostao.tostao_api.membership;

import static com.tostao.tostao_api.membership.MembershipScenario.invitationsFingerprint;
import static com.tostao.tostao_api.membership.MembershipScenario.invitationsUrl;
import static com.tostao.tostao_api.membership.MembershipScenario.membersFingerprint;
import static com.tostao.tostao_api.membership.MembershipScenario.membersUrl;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * História 4: só o dono gerencia, e tudo fica no seu workspace. Acesso cruzado e papéis para cada uma das operações
 * (FR-015 a FR-017, SC-006, SC-007).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MembershipAccessTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private MembershipScenario scenario() {
		return MembershipScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	/** Um convite pendente do dono, para as operações que precisam de um id. */
	private String pendingInvitation(MembershipScenario s) throws Exception {
		return JsonPath.read(MembershipScenario.invite(mvc, s.owner(), s.workspace(), "{\"email\":\"" + UUID.randomUUID() + "@exemplo.com\",\"role\":\"EDITOR\"}")
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id");
	}

	/**
	 * As seis operações de um workspace, como a pessoa dada (nula = sem sessão), contra o workspace, o membro e o convite
	 * dados: listar membros, mudar papel, remover, convidar, listar convites e revogar.
	 */
	private ResultActions[] operations(UUID workspaceId, Fixture who, UUID targetUser, String invitationId) throws Exception {
		MockHttpServletRequestBuilder[] requests = {
				get(membersUrl(workspaceId)),
				put(membersUrl(workspaceId) + "/" + targetUser).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"VIEWER\"}"),
				delete(membersUrl(workspaceId) + "/" + targetUser),
				post(invitationsUrl(workspaceId)).contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"novo@exemplo.com\",\"role\":\"EDITOR\"}"),
				get(invitationsUrl(workspaceId)),
				delete(invitationsUrl(workspaceId) + "/" + invitationId) };
		ResultActions[] results = new ResultActions[requests.length];
		for (int i = 0; i < requests.length; i++) {
			results[i] = mvc.perform(who == null ? requests[i] : requests[i].cookie(session(who.token())));
		}
		return results;
	}

	private String fingerprints(UUID workspaceId) {
		return membersFingerprint(owner, workspaceId) + "|" + invitationsFingerprint(owner, workspaceId);
	}

	@Test
	void aNonMemberGetsTheSameNotFoundAsAMissingWorkspaceOnEveryOperationAndNothingChanges() throws Exception {
		MembershipScenario s = scenario();
		String invitation = pendingInvitation(s);
		String before = fingerprints(s.workspace());

		ResultActions[] real = operations(s.workspace(), s.outsider(), s.editor().userId(), invitation);
		ResultActions[] missing = operations(UUID.randomUUID(), s.outsider(), s.editor().userId(), invitation);

		for (int i = 0; i < 6; i++) {
			real[i].andExpect(status().isNotFound());
			missing[i].andExpect(status().isNotFound());
			String a = real[i].andReturn().getResponse().getContentAsString();
			String b = missing[i].andReturn().getResponse().getContentAsString();
			assertThat((String) JsonPath.read(a, "$.title")).as("operação %d".formatted(i)).isEqualTo(JsonPath.read(b, "$.title"));
			assertThat((String) JsonPath.read(a, "$.detail")).as("operação %d".formatted(i)).isEqualTo(JsonPath.read(b, "$.detail"));
		}
		assertThat(fingerprints(s.workspace())).isEqualTo(before);
	}

	@Test
	void editorsAndViewersOnlyListTheMembersAndEveryManagementOperationIsForbidden() throws Exception {
		MembershipScenario s = scenario();
		String invitation = pendingInvitation(s);

		for (Fixture who : new Fixture[] { s.editor(), s.viewer() }) {
			String before = fingerprints(s.workspace());
			Fixture other = who == s.editor() ? s.viewer() : s.editor();

			ResultActions[] results = operations(s.workspace(), who, other.userId(), invitation);

			results[0].andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
			for (int i = 1; i < 6; i++) {
				results[i].andExpect(status().isForbidden());
			}
			assertThat(fingerprints(s.workspace())).as("nada mudou").isEqualTo(before);
		}
	}

	@Test
	void theOwnerDoesEverythingTheRoleAllows() throws Exception {
		MembershipScenario s = scenario();
		String invitation = pendingInvitation(s);

		ResultActions[] results = operations(s.workspace(), s.owner(), s.viewer().userId(), invitation);

		results[0].andExpect(status().isOk());
		results[1].andExpect(status().isOk());
		results[2].andExpect(status().isNoContent());
		results[3].andExpect(status().isCreated());
		results[4].andExpect(status().isOk());
		results[5].andExpect(status().isNoContent());
	}

	@Test
	void withoutASessionEveryOperationIsRefusedIncludingTheAccept() throws Exception {
		MembershipScenario s = scenario();
		String invitation = pendingInvitation(s);
		String before = fingerprints(s.workspace());

		for (ResultActions result : operations(s.workspace(), null, s.editor().userId(), invitation)) {
			result.andExpect(status().isUnauthorized());
		}
		mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"x\"}"))
				.andExpect(status().isUnauthorized());

		assertThat(fingerprints(s.workspace())).isEqualTo(before);
	}

	@Test
	void anInvitationAndAMemberOfAnotherWorkspaceAreNeverReachedByTheirIds() throws Exception {
		MembershipScenario mine = scenario();
		MembershipScenario theirs = scenario();
		String foreignInvitation = pendingInvitation(theirs);
		String theirBefore = fingerprints(theirs.workspace());

		// o convite e o membro do outro, nas rotas do meu workspace
		ResultActions[] results = operations(mine.workspace(), mine.owner(), theirs.editor().userId(), foreignInvitation);

		results[1].andExpect(status().isNotFound());
		results[2].andExpect(status().isNotFound());
		results[5].andExpect(status().isNotFound());
		assertThat(fingerprints(theirs.workspace())).isEqualTo(theirBefore);
		// a lista de convites de cada um só tem os próprios
		String mineListed = results[4].andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<String>>read(mineListed, "$[*].id")).doesNotContain(foreignInvitation);
	}

	@Test
	void theOwnerOfOneWorkspaceIsNotAMemberOfAnotherAndGetsNotFoundThere() throws Exception {
		MembershipScenario a = scenario();
		MembershipScenario b = scenario();
		String invitationOfB = pendingInvitation(b);
		String bBefore = fingerprints(b.workspace());

		// o dono de A tenta gerir B
		ResultActions[] results = operations(b.workspace(), a.owner(), b.editor().userId(), invitationOfB);

		for (ResultActions result : results) {
			result.andExpect(status().isNotFound());
		}
		assertThat(fingerprints(b.workspace())).isEqualTo(bBefore);
	}

	@Test
	void aMalformedMemberOrInvitationIdIsNotFound() throws Exception {
		MembershipScenario s = scenario();

		mvc.perform(put(membersUrl(s.workspace()) + "/abc").cookie(session(s.owner().token())).contentType(MediaType.APPLICATION_JSON)
				.content("{\"role\":\"VIEWER\"}")).andExpect(status().isNotFound());
		mvc.perform(delete(membersUrl(s.workspace()) + "/abc").cookie(session(s.owner().token()))).andExpect(status().isNotFound());
		mvc.perform(delete(invitationsUrl(s.workspace()) + "/abc").cookie(session(s.owner().token()))).andExpect(status().isNotFound());
	}

}
