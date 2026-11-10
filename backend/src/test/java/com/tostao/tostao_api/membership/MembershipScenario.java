package com.tostao.tostao_api.membership;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * Cenário da 012: um workspace com o dono, um editor e um leitor (semeados pelo banco), mais uma pessoa de fora com o
 * próprio workspace. Helpers de rota e de impressão digital das tabelas de membros e convites.
 */
public record MembershipScenario(Fixture owner, Fixture editor, Fixture viewer, Fixture outsider) {

	public static MembershipScenario create(WorkspaceFixtures fixtures) {
		Fixture owner = fixtures.workspaceWithOwner();
		Fixture editor = fixtures.addMember(owner.workspaceId(), "EDITOR");
		Fixture viewer = fixtures.addMember(owner.workspaceId(), "VIEWER");
		return new MembershipScenario(owner, editor, viewer, fixtures.workspaceWithOwner());
	}

	public UUID workspace() {
		return owner.workspaceId();
	}

	public static String membersUrl(UUID workspaceId) {
		return "/api/v1/workspaces/" + workspaceId + "/members";
	}

	public static String invitationsUrl(UUID workspaceId) {
		return "/api/v1/workspaces/" + workspaceId + "/invitations";
	}

	public static ResultActions listMembers(MockMvc mvc, Fixture who, UUID workspaceId) throws Exception {
		return mvc.perform(get(membersUrl(workspaceId)).cookie(session(who.token())));
	}

	/** {@code PUT /members/{userId}} com o corpo dado; o id é texto para os testes de identificador malformado. */
	public static ResultActions changeRole(MockMvc mvc, Fixture who, UUID workspaceId, Object userId, String json) throws Exception {
		return mvc.perform(put(membersUrl(workspaceId) + "/" + userId).cookie(session(who.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	public static ResultActions changeRole(MockMvc mvc, Fixture who, UUID workspaceId, Object userId, String role, boolean quoted)
			throws Exception {
		return changeRole(mvc, who, workspaceId, userId, "{\"role\":" + (quoted ? "\"" + role + "\"" : role) + "}");
	}

	public static ResultActions removeMember(MockMvc mvc, Fixture who, UUID workspaceId, Object userId) throws Exception {
		return mvc.perform(delete(membersUrl(workspaceId) + "/" + userId).cookie(session(who.token())));
	}

	/** {@code POST /invitations} com o corpo dado. */
	public static ResultActions invite(MockMvc mvc, Fixture who, UUID workspaceId, String json) throws Exception {
		return mvc.perform(post(invitationsUrl(workspaceId)).cookie(session(who.token())).contentType(MediaType.APPLICATION_JSON)
				.content(json));
	}

	public static ResultActions listInvitations(MockMvc mvc, Fixture who, UUID workspaceId) throws Exception {
		return mvc.perform(get(invitationsUrl(workspaceId)).cookie(session(who.token())));
	}

	public static ResultActions revokeInvitation(MockMvc mvc, Fixture who, UUID workspaceId, Object id) throws Exception {
		return mvc.perform(delete(invitationsUrl(workspaceId) + "/" + id).cookie(session(who.token())));
	}

	/** {@code POST /api/v1/invitations/accept} com o corpo dado. */
	public static ResultActions accept(MockMvc mvc, Fixture who, String json) throws Exception {
		return mvc.perform(post("/api/v1/invitations/accept").cookie(session(who.token())).contentType(MediaType.APPLICATION_JSON)
				.content(json));
	}

	/** Impressão digital de {@code workspace_members} do workspace (papel e data incluídos). */
	public static String membersFingerprint(OwnerJdbc owner, UUID workspaceId) {
		return owner.client().sql("select coalesce(md5(string_agg(x::text, ',' order by x.user_id)), 'vazio') "
				+ "from workspace_members x where x.workspace_id = :w").param("w", workspaceId).query(String.class).single();
	}

	/** Impressão digital de {@code invitations} do workspace. */
	public static String invitationsFingerprint(OwnerJdbc owner, UUID workspaceId) {
		return owner.client().sql("select coalesce(md5(string_agg(x::text, ',' order by x.id)), 'vazio') "
				+ "from invitations x where x.workspace_id = :w").param("w", workspaceId).query(String.class).single();
	}

	public static String roleOf(OwnerJdbc owner, UUID workspaceId, UUID userId) {
		return owner.client().sql("select role from workspace_members where workspace_id = :w and user_id = :u")
				.param("w", workspaceId).param("u", userId).query(String.class).optional().orElse(null);
	}

}
