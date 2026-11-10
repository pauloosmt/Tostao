package com.tostao.tostao_api.membership;

import static com.tostao.tostao_api.membership.MembershipScenario.accept;
import static com.tostao.tostao_api.membership.MembershipScenario.invitationsFingerprint;
import static com.tostao.tostao_api.membership.MembershipScenario.invite;
import static com.tostao.tostao_api.membership.MembershipScenario.listMembers;
import static com.tostao.tostao_api.membership.MembershipScenario.membersFingerprint;
import static com.tostao.tostao_api.membership.MembershipScenario.revokeInvitation;
import static com.tostao.tostao_api.membership.MembershipScenario.roleOf;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.AuthSupport;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** História 1 (aceitar): a pessoa convidada entra no workspace com o código. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AcceptInvitationTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private static String codeJson(String code) {
		return "{\"code\":\"" + code + "\"}";
	}

	/** Uma pessoa já cadastrada (pelo banco) com o e-mail dado, com sessão própria. O workspace da fixture não é usado. */
	private Fixture person(String email) {
		UUID user = fixtures().newUser();
		owner.client().sql("update users set email = :e where id = :u").param("e", email).param("u", user).update();
		return new Fixture(UUID.randomUUID(), user, fixtures().tokenOf(user));
	}

	private String uniqueEmail() {
		return "convidado-" + UUID.randomUUID() + "@exemplo.com";
	}

	/** O dono convida pela API e devolve o código. */
	private String invitation(MembershipScenario s, String email, String role) throws Exception {
		String created = body(invite(mvc, s.owner(), s.workspace(), "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}")
				.andExpect(status().isCreated()));
		return JsonPath.read(created, "$.code");
	}

	// ---- aceitar ----------------------------------------------------------------------------------------------

	@Test
	void theInvitedPersonBecomesAMemberWithTheRoleOfTheInvitation() throws Exception {
		MembershipScenario s = scenario();
		for (String role : new String[] { "EDITOR", "VIEWER" }) {
			String email = uniqueEmail();
			Fixture guest = person(email);
			String code = invitation(s, email, role);

			accept(mvc, guest, codeJson(code)).andExpect(status().isOk())
					.andExpect(jsonPath("$.workspaceId").value(s.workspace().toString()))
					.andExpect(jsonPath("$.name").value("Fixture"))
					.andExpect(jsonPath("$.role").value(role))
					.andExpect(header().string("Cache-Control", containsString("no-store")));

			assertThat(roleOf(owner, s.workspace(), guest.userId())).isEqualTo(role);
		}
	}

	private MembershipScenario scenario() {
		return MembershipScenario.create(fixtures());
	}

	@Test
	void theNewRoleActsOnTheVeryNextRequestWithoutANewLogin() throws Exception {
		MembershipScenario s = scenario();
		String editorEmail = uniqueEmail();
		String viewerEmail = uniqueEmail();
		Fixture editor = person(editorEmail);
		Fixture viewer = person(viewerEmail);
		String account = "{\"name\":\"Conta\",\"type\":\"CHECKING\",\"initialBalanceCents\":0}";
		String accountsUrl = "/api/v1/workspaces/" + s.workspace() + "/accounts";

		// antes de aceitar, o workspace não existe para eles
		mvc.perform(get(accountsUrl).cookie(session(editor.token()))).andExpect(status().isNotFound());
		accept(mvc, editor, codeJson(invitation(s, editorEmail, "EDITOR"))).andExpect(status().isOk());
		accept(mvc, viewer, codeJson(invitation(s, viewerEmail, "VIEWER"))).andExpect(status().isOk());

		// o mesmo cookie de antes: o editor escreve, o leitor só lê
		mvc.perform(post(accountsUrl).cookie(session(editor.token())).contentType(MediaType.APPLICATION_JSON).content(account))
				.andExpect(status().isCreated());
		mvc.perform(post(accountsUrl).cookie(session(viewer.token())).contentType(MediaType.APPLICATION_JSON).content(account))
				.andExpect(status().isForbidden());
		mvc.perform(get(accountsUrl).cookie(session(viewer.token()))).andExpect(status().isOk());
		listMembers(mvc, viewer, s.workspace()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
	}

	@Test
	void thePersonSeesTheNewWorkspaceTogetherWithThePersonalOne() throws Exception {
		MembershipScenario s = scenario();
		String email = uniqueEmail();
		Fixture guest = person(email);
		UUID personal = owner.client().sql("insert into workspaces (name) values ('Pessoal') returning id").query(UUID.class).single();
		owner.client().sql("insert into workspace_members (workspace_id, user_id, role) values (:w, :u, 'OWNER')")
				.param("w", personal).param("u", guest.userId()).update();

		accept(mvc, guest, codeJson(invitation(s, email, "VIEWER"))).andExpect(status().isOk());

		String me = body(mvc.perform(get("/api/v1/me").cookie(session(guest.token()))).andExpect(status().isOk()));
		assertThat(JsonPath.<List<String>>read(me, "$.workspaces[*].id")).containsExactlyInAnyOrder(personal.toString(), s.workspace().toString());
		assertThat(JsonPath.<List<String>>read(me, "$.workspaces[?(@.id=='" + s.workspace() + "')].role")).containsExactly("VIEWER");
		assertThat(JsonPath.<List<String>>read(me, "$.workspaces[?(@.id=='" + personal + "')].role")).containsExactly("OWNER");
	}

	@Test
	void theWholeFlowWorksWithAnAccountRegisteredThroughTheApi() throws Exception {
		MembershipScenario s = scenario();
		String email = "Marido-" + UUID.randomUUID() + "@Exemplo.com";
		// a pessoa se cadastra (o e-mail é normalizado), recebe o código do dono e aceita
		MvcResult registered = AuthSupport.register(mvc, email);
		assertThat(registered.getResponse().getStatus()).isEqualTo(201);
		String token = AuthSupport.tokenOf(registered);
		Fixture guest = new Fixture(UUID.randomUUID(), UUID.fromString(JsonPath.read(registered.getResponse().getContentAsString(), "$.id")), token);

		String code = invitation(s, email, "EDITOR");
		accept(mvc, guest, codeJson(code)).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("EDITOR"));

		String me = body(mvc.perform(get("/api/v1/me").cookie(session(token))).andExpect(status().isOk()));
		assertThat((List<?>) JsonPath.read(me, "$.workspaces")).hasSize(2);
		assertThat(JsonPath.<List<String>>read(me, "$.workspaces[*].id")).contains(s.workspace().toString());
	}

	@Test
	void anEmailStoredWithADifferentCaseStillMatches() throws Exception {
		MembershipScenario s = scenario();
		String email = uniqueEmail();
		Fixture guest = person(email.toUpperCase());

		accept(mvc, guest, codeJson(invitation(s, email, "VIEWER"))).andExpect(status().isOk());
	}

	@Test
	void theCodeWorksOnlyOnce() throws Exception {
		MembershipScenario s = scenario();
		String email = uniqueEmail();
		Fixture guest = person(email);
		String code = invitation(s, email, "EDITOR");

		accept(mvc, guest, codeJson(code)).andExpect(status().isOk());
		accept(mvc, guest, codeJson(code)).andExpect(status().isNotFound());
		// e outra pessoa com o código já usado também
		accept(mvc, person(uniqueEmail()), codeJson(code)).andExpect(status().isNotFound());
		assertThat(membersFingerprint(owner, s.workspace())).isNotNull();
	}

	// ---- código ruim: sempre a mesma resposta ------------------------------------------------------------------

	private record Answer(int status, String title, String detail) {
	}

	private Answer answerOf(Fixture who, String json) throws Exception {
		var response = accept(mvc, who, json).andReturn().getResponse();
		String content = response.getContentAsString();
		return new Answer(response.getStatus(), JsonPath.read(content, "$.title"), JsonPath.read(content, "$.detail"));
	}

	@Test
	void everyKindOfBadCodeGetsExactlyTheSameAnswer() throws Exception {
		MembershipScenario s = scenario();
		String email = uniqueEmail();
		Fixture guest = person(email);

		String used = invitation(s, email, "EDITOR");
		accept(mvc, guest, codeJson(used)).andExpect(status().isOk());
		String otherEmail = uniqueEmail();
		Fixture other = person(otherEmail);
		String revokedCode = invitation(s, otherEmail, "EDITOR");
		String revokedId = JsonPath.read(body(com.tostao.tostao_api.membership.MembershipScenario.listInvitations(mvc, s.owner(), s.workspace())), "$[0].id");
		revokeInvitation(mvc, s.owner(), s.workspace(), revokedId).andExpect(status().isNoContent());
		String expiredEmail = uniqueEmail();
		Fixture expiredGuest = person(expiredEmail);
		String expiredCode = invitation(s, expiredEmail, "EDITOR");
		owner.client().sql("update invitations set expires_at = now() - interval '1 second', created_at = now() - interval '8 days' where email = :e")
				.param("e", expiredEmail).update();
		String forSomeoneElse = invitation(s, uniqueEmail(), "EDITOR");

		Answer unknown = answerOf(other, codeJson("codigo-que-nao-existe"));
		assertThat(unknown.status()).isEqualTo(404);
		assertThat(unknown.detail()).isEqualTo("Convite inválido ou expirado");
		assertThat(answerOf(guest, codeJson(used))).as("usado").isEqualTo(unknown);
		assertThat(answerOf(other, codeJson(revokedCode))).as("revogado").isEqualTo(unknown);
		assertThat(answerOf(expiredGuest, codeJson(expiredCode))).as("expirado").isEqualTo(unknown);
		assertThat(answerOf(other, codeJson(forSomeoneElse))).as("de outro e-mail").isEqualTo(unknown);
		// tamanho e formato errados também, sem virar 400
		assertThat(answerOf(other, codeJson("abc"))).as("curto").isEqualTo(unknown);
		assertThat(answerOf(other, codeJson("x".repeat(200)))).as("longo, no limite").isEqualTo(unknown);
		assertThat(answerOf(other, codeJson("com espaço e símbolos !@#"))).as("formato errado").isEqualTo(unknown);
	}

	@Test
	void anotherEmailWithTheRightCodeIsRefusedAndTheInvitationStaysUsableByTheRightPerson() throws Exception {
		MembershipScenario s = scenario();
		String email = uniqueEmail();
		Fixture right = person(email);
		Fixture wrong = person(uniqueEmail());
		String code = invitation(s, email, "EDITOR");
		String before = invitationsFingerprint(owner, s.workspace());

		accept(mvc, wrong, codeJson(code)).andExpect(status().isNotFound());

		assertThat(invitationsFingerprint(owner, s.workspace())).isEqualTo(before);
		assertThat(roleOf(owner, s.workspace(), wrong.userId())).isNull();
		accept(mvc, right, codeJson(code)).andExpect(status().isOk());
	}

	@Test
	void alreadyBeingAMemberIsAConflictAndDoesNotConsumeTheInvitation() throws Exception {
		MembershipScenario s = scenario();
		String email = owner.client().sql("select email from users where id = :id").param("id", s.editor().userId()).query(String.class).single();
		// um convite que já estava pendente quando a pessoa entrou por outro meio
		String code = "codigo-semeado-" + UUID.randomUUID();
		owner.client().sql("""
				insert into invitations (workspace_id, email, role, code_hash, created_by, expires_at)
				values (:w, :e, 'VIEWER', encode(sha256(convert_to(:c, 'UTF8')), 'hex'), :u, now() + interval '7 days')""")
				.param("w", s.workspace()).param("e", email).param("c", code).param("u", s.owner().userId()).update();
		String before = invitationsFingerprint(owner, s.workspace());

		accept(mvc, s.editor(), codeJson(code)).andExpect(status().isConflict());

		assertThat(roleOf(owner, s.workspace(), s.editor().userId())).as("o papel não muda").isEqualTo("EDITOR");
		assertThat(invitationsFingerprint(owner, s.workspace())).isEqualTo(before);
	}

	@Test
	void aWorkspaceWithTwentyMembersIsAConflict() throws Exception {
		MembershipScenario s = scenario();
		String email = uniqueEmail();
		Fixture guest = person(email);
		String code = invitation(s, email, "EDITOR");
		for (int i = 0; i < 17; i++) {
			fixtures().addMember(s.workspace(), "VIEWER");
		}

		accept(mvc, guest, codeJson(code)).andExpect(status().isConflict());

		assertThat(roleOf(owner, s.workspace(), guest.userId())).isNull();
	}

	@Test
	void theCodeOfOneWorkspaceOnlyGivesThatMembership() throws Exception {
		MembershipScenario a = scenario();
		MembershipScenario b = scenario();
		String email = uniqueEmail();
		Fixture guest = person(email);
		String code = invitation(a, email, "VIEWER");
		String bBefore = membersFingerprint(owner, b.workspace());

		accept(mvc, guest, codeJson(code)).andExpect(status().isOk());

		assertThat(roleOf(owner, a.workspace(), guest.userId())).isEqualTo("VIEWER");
		assertThat(roleOf(owner, b.workspace(), guest.userId())).isNull();
		assertThat(membersFingerprint(owner, b.workspace())).isEqualTo(bBefore);
	}

	// ---- entrada ----------------------------------------------------------------------------------------------

	@Test
	void withoutASessionTheAnswerIsUnauthorized() throws Exception {
		mvc.perform(post("/api/v1/invitations/accept").contentType(MediaType.APPLICATION_JSON).content(codeJson("qualquer")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void aMalformedBodyIsRefused() throws Exception {
		Fixture guest = person(uniqueEmail());

		for (String json : new String[] { "{}", "{\"code\":\"\"}", "{\"code\":\"   \"}", "{\"code\":null}", "{\"code\":\"" + "x".repeat(201) + "\"}",
				"{\"code\":\"abc\",\"workspaceId\":\"" + UUID.randomUUID() + "\"}", "{\"code\":\"abc\",\"role\":\"OWNER\"}", "{", "[]", "" }) {
			accept(mvc, guest, json).andExpect(status().isBadRequest());
		}
	}

}
