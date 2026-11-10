package com.tostao.tostao_api.membership;

import static com.tostao.tostao_api.membership.MembershipScenario.invitationsFingerprint;
import static com.tostao.tostao_api.membership.MembershipScenario.invite;
import static com.tostao.tostao_api.membership.MembershipScenario.listInvitations;
import static com.tostao.tostao_api.membership.MembershipScenario.revokeInvitation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/** História 1 (convidar) e 3 (listar e revogar): o dono convida, vê os pendentes e revoga. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CreateInvitationTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private MembershipScenario scenario() {
		return MembershipScenario.create(fixtures());
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private static String json(String email, String role) {
		return "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}";
	}

	/** O SHA-256 do código em hexadecimal, calculado aqui de forma independente da classe de produção. */
	private static String sha256(String code) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
	}

	private long pendingCount(UUID workspace) {
		return owner.client().sql("select count(*) from invitations where workspace_id = :w and accepted_at is null and revoked_at is null")
				.param("w", workspace).query(Long.class).single();
	}

	private void seedPending(UUID workspace, UUID createdBy, int count) throws Exception {
		for (int i = 0; i < count; i++) {
			owner.client().sql("""
					insert into invitations (workspace_id, email, role, code_hash, created_by, expires_at)
					values (:w, :e, 'VIEWER', :h, :u, now() + interval '7 days')""")
					.param("w", workspace).param("e", "semeado" + i + "-" + UUID.randomUUID() + "@x.com")
					.param("h", sha256(UUID.randomUUID().toString())).param("u", createdBy).update();
		}
	}

	// ---- convidar ---------------------------------------------------------------------------------------------

	@Test
	void theOwnerInvitesAnEditorAndAViewerAndGetsTheSecretCode() throws Exception {
		MembershipScenario s = scenario();

		for (String role : new String[] { "EDITOR", "VIEWER" }) {
			Instant before = Instant.now();
			String created = body(invite(mvc, s.owner(), s.workspace(), json("ana-" + role + "@exemplo.com", role)).andExpect(status().isCreated())
					.andExpect(jsonPath("$.email").value("ana-" + role.toLowerCase() + "@exemplo.com"))
					.andExpect(jsonPath("$.role").value(role))
					.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString()))
					.andExpect(jsonPath("$.id").isNotEmpty()));

			assertThat((String) JsonPath.read(created, "$.code")).hasSize(43).matches("[A-Za-z0-9_-]{43}");
			Instant expires = Instant.parse(JsonPath.read(created, "$.expiresAt"));
			assertThat(Duration.between(before, expires)).isBetween(Duration.ofDays(7).minusMinutes(5), Duration.ofDays(7).plusMinutes(5));
		}
	}

	@Test
	void theEmailIsNormalizedLikeInTheRegistration() throws Exception {
		MembershipScenario s = scenario();

		invite(mvc, s.owner(), s.workspace(), json("  Ana@Exemplo.COM ", "EDITOR")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value("ana@exemplo.com"));

		assertThat(owner.client().sql("select email from invitations where workspace_id = :w").param("w", s.workspace())
				.query(String.class).single()).isEqualTo("ana@exemplo.com");
	}

	@Test
	void theResponseIsNeverCached() throws Exception {
		MembershipScenario s = scenario();

		invite(mvc, s.owner(), s.workspace(), json("ana@exemplo.com", "EDITOR")).andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", containsString("no-store")));
	}

	@Test
	void theDatabaseKeepsOnlyTheHashOfTheCode() throws Exception {
		MembershipScenario s = scenario();

		String created = body(invite(mvc, s.owner(), s.workspace(), json("ana@exemplo.com", "EDITOR")).andExpect(status().isCreated()));
		String code = JsonPath.read(created, "$.code");

		assertThat(owner.client().sql("select code_hash from invitations where workspace_id = :w").param("w", s.workspace())
				.query(String.class).single()).isEqualTo(sha256(code));
		// o código não está em coluna alguma da linha
		assertThat(owner.client().sql("select x::text from invitations x where x.workspace_id = :w").param("w", s.workspace())
				.query(String.class).single()).doesNotContain(code);
	}

	@Test
	void anEmailWithoutAnAccountYetCanBeInvited() throws Exception {
		MembershipScenario s = scenario();

		invite(mvc, s.owner(), s.workspace(), json("ninguem-" + UUID.randomUUID() + "@exemplo.com", "VIEWER")).andExpect(status().isCreated());
	}

	@Test
	void anEmailThatIsAlreadyAMemberIsAConflictAndNothingIsCreated() throws Exception {
		MembershipScenario s = scenario();
		String memberEmail = owner.client().sql("select email from users where id = :id").param("id", s.editor().userId())
				.query(String.class).single();
		String before = invitationsFingerprint(owner, s.workspace());

		invite(mvc, s.owner(), s.workspace(), json(memberEmail, "VIEWER")).andExpect(status().isConflict());
		// também com variação de caixa e espaços
		invite(mvc, s.owner(), s.workspace(), json("  " + memberEmail.toUpperCase() + " ", "VIEWER")).andExpect(status().isConflict());
		// e o dono convidando o próprio e-mail
		String ownerEmail = owner.client().sql("select email from users where id = :id").param("id", s.owner().userId())
				.query(String.class).single();
		invite(mvc, s.owner(), s.workspace(), json(ownerEmail, "EDITOR")).andExpect(status().isConflict());

		assertThat(invitationsFingerprint(owner, s.workspace())).isEqualTo(before);
	}

	@Test
	void invitingTheSameEmailAgainInvalidatesThePreviousInvitation() throws Exception {
		MembershipScenario s = scenario();
		String first = body(invite(mvc, s.owner(), s.workspace(), json("ana@exemplo.com", "EDITOR")).andExpect(status().isCreated()));

		String second = body(invite(mvc, s.owner(), s.workspace(), json("ana@exemplo.com", "VIEWER")).andExpect(status().isCreated()));

		assertThat((String) JsonPath.read(second, "$.code")).isNotEqualTo((String) JsonPath.read(first, "$.code"));
		assertThat(pendingCount(s.workspace())).isEqualTo(1L);
		assertThat(owner.client().sql("select revoked_at is not null from invitations where code_hash = :h")
				.param("h", sha256(JsonPath.read(first, "$.code"))).query(Boolean.class).single()).isTrue();
		listInvitations(mvc, s.owner(), s.workspace()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].role").value("VIEWER")).andExpect(jsonPath("$[0].id").value((String) JsonPath.read(second, "$.id")));
	}

	@Test
	void theLimitOfTwentyPendingInvitationsIsEnforcedAndReinvitingStillWorks() throws Exception {
		MembershipScenario s = scenario();
		seedPending(s.workspace(), s.owner().userId(), 19);
		invite(mvc, s.owner(), s.workspace(), json("vinte@exemplo.com", "EDITOR")).andExpect(status().isCreated());
		String before = invitationsFingerprint(owner, s.workspace());

		invite(mvc, s.owner(), s.workspace(), json("vinte-e-um@exemplo.com", "EDITOR")).andExpect(status().isConflict());

		assertThat(invitationsFingerprint(owner, s.workspace())).as("o conflito desfaz tudo").isEqualTo(before);
		assertThat(pendingCount(s.workspace())).isEqualTo(20L);
		// reconvidar um e-mail que já tem convite pendente troca o convite e não esbarra no limite
		invite(mvc, s.owner(), s.workspace(), json("vinte@exemplo.com", "VIEWER")).andExpect(status().isCreated());
		assertThat(pendingCount(s.workspace())).isEqualTo(20L);
	}

	@Test
	void theLimitOfTwentyMembersIsEnforced() throws Exception {
		MembershipScenario s = scenario();
		for (int i = 0; i < 17; i++) {
			fixtures().addMember(s.workspace(), "VIEWER");
		}
		assertThat(owner.client().sql("select count(*) from workspace_members where workspace_id = :w").param("w", s.workspace())
				.query(Long.class).single()).isEqualTo(20L);
		String before = invitationsFingerprint(owner, s.workspace());

		invite(mvc, s.owner(), s.workspace(), json("novo@exemplo.com", "EDITOR")).andExpect(status().isConflict());

		assertThat(invitationsFingerprint(owner, s.workspace())).isEqualTo(before);
	}

	// ---- validação: nada é gravado ----------------------------------------------------------------------------

	private void refuse(MembershipScenario s, String field, String json) throws Exception {
		String before = invitationsFingerprint(owner, s.workspace());
		ResultActions result = invite(mvc, s.owner(), s.workspace(), json).andExpect(status().isBadRequest());
		if (field != null) {
			assertThat(JsonPath.<List<String>>read(body(result), "$.errors[*].field")).as(json).contains(field);
		}
		assertThat(invitationsFingerprint(owner, s.workspace())).as("nada gravado para " + json).isEqualTo(before);
	}

	@Test
	void anInvalidRoleIsRefusedOnItsField() throws Exception {
		MembershipScenario s = scenario();

		for (String role : new String[] { "\"OWNER\"", "\"OUTRO\"", "\"\"", "\"editor\"", "null" }) {
			refuse(s, "role", "{\"email\":\"ana@exemplo.com\",\"role\":" + role + "}");
		}
		refuse(s, "role", "{\"email\":\"ana@exemplo.com\"}");
	}

	@Test
	void anInvalidEmailIsRefusedOnItsField() throws Exception {
		MembershipScenario s = scenario();

		for (String email : new String[] { "\"\"", "\"   \"", "\"abc\"", "\"sem-arroba.com\"", "\"@x.com\"", "null", "\"" + "a".repeat(250) + "@x.com\"" }) {
			refuse(s, "email", "{\"email\":" + email + ",\"role\":\"EDITOR\"}");
		}
		refuse(s, "email", "{\"role\":\"EDITOR\"}");
	}

	@Test
	void fieldsOutsideTheAllowedListAreRefused() throws Exception {
		MembershipScenario s = scenario();

		for (String extra : new String[] { "createdBy", "workspaceId", "status", "code", "id", "expiresAt", "acceptedAt" }) {
			refuse(s, null, "{\"email\":\"ana@exemplo.com\",\"role\":\"EDITOR\",\"" + extra + "\":\"" + UUID.randomUUID() + "\"}");
		}
		refuse(s, null, "{");
		refuse(s, null, "[]");
	}

	// ---- listar e revogar -------------------------------------------------------------------------------------

	@Test
	void theListHasOnlyThePendingInvitationsWithoutTheCode() throws Exception {
		MembershipScenario s = scenario();
		invite(mvc, s.owner(), s.workspace(), json("pendente@exemplo.com", "EDITOR")).andExpect(status().isCreated());
		String used = body(invite(mvc, s.owner(), s.workspace(), json("usado@exemplo.com", "EDITOR")).andExpect(status().isCreated()));
		String revoked = body(invite(mvc, s.owner(), s.workspace(), json("revogado@exemplo.com", "EDITOR")).andExpect(status().isCreated()));
		String expired = body(invite(mvc, s.owner(), s.workspace(), json("expirado@exemplo.com", "EDITOR")).andExpect(status().isCreated()));
		owner.client().sql("update invitations set accepted_at = now(), accepted_by = :u where id = cast(:id as uuid)")
				.param("u", s.viewer().userId()).param("id", (String) JsonPath.read(used, "$.id")).update();
		owner.client().sql("update invitations set revoked_at = now() where id = cast(:id as uuid)")
				.param("id", (String) JsonPath.read(revoked, "$.id")).update();
		owner.client().sql("update invitations set expires_at = now() - interval '1 second', created_at = now() - interval '8 days' where id = cast(:id as uuid)")
				.param("id", (String) JsonPath.read(expired, "$.id")).update();

		String listed = body(listInvitations(mvc, s.owner(), s.workspace()).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store"))));

		assertThat(JsonPath.<List<String>>read(listed, "$[*].email")).containsExactly("pendente@exemplo.com");
		List<Map<String, Object>> items = JsonPath.read(listed, "$");
		assertThat(items.get(0).keySet()).containsExactlyInAnyOrder("id", "email", "role", "createdBy", "createdAt", "expiresAt");
		assertThat(listed).doesNotContain("code");
	}

	@Test
	void revokingTakesTheInvitationOutOfTheListAndTheCodeStopsWorking() throws Exception {
		MembershipScenario s = scenario();
		String created = body(invite(mvc, s.owner(), s.workspace(), json("ana@exemplo.com", "EDITOR")).andExpect(status().isCreated()));
		String id = JsonPath.read(created, "$.id");

		revokeInvitation(mvc, s.owner(), s.workspace(), id).andExpect(status().isNoContent())
				.andExpect(header().string("Cache-Control", containsString("no-store")));

		listInvitations(mvc, s.owner(), s.workspace()).andExpect(jsonPath("$.length()").value(0));
		UUID guest = fixtures().newUser();
		owner.client().sql("update users set email = 'ana@exemplo.com' where id = :u").param("u", guest).update();
		assertThat(owner.client().sql("select outcome from accept_invitation(:h, :u)").param("h", sha256(JsonPath.read(created, "$.code")))
				.param("u", guest).query(String.class).single()).isEqualTo("INVALID");
	}

	@Test
	void revokingAnUnavailableInvitationIsNotFoundAndNothingChanges() throws Exception {
		MembershipScenario s = scenario();
		MembershipScenario other = scenario();
		String revoked = JsonPath.read(body(invite(mvc, s.owner(), s.workspace(), json("a@exemplo.com", "EDITOR"))), "$.id");
		revokeInvitation(mvc, s.owner(), s.workspace(), revoked).andExpect(status().isNoContent());
		String used = JsonPath.read(body(invite(mvc, s.owner(), s.workspace(), json("b@exemplo.com", "EDITOR"))), "$.id");
		owner.client().sql("update invitations set accepted_at = now(), accepted_by = :u where id = cast(:id as uuid)")
				.param("u", s.viewer().userId()).param("id", used).update();
		String foreign = JsonPath.read(body(invite(mvc, other.owner(), other.workspace(), json("c@exemplo.com", "EDITOR"))), "$.id");
		String mine = invitationsFingerprint(owner, s.workspace());
		String theirs = invitationsFingerprint(owner, other.workspace());

		for (Object id : new Object[] { revoked, used, foreign, UUID.randomUUID(), "abc" }) {
			revokeInvitation(mvc, s.owner(), s.workspace(), id).andExpect(status().isNotFound());
		}

		assertThat(invitationsFingerprint(owner, s.workspace())).isEqualTo(mine);
		assertThat(invitationsFingerprint(owner, other.workspace())).isEqualTo(theirs);
	}

}
