package com.tostao.tostao_api.membership;

import static com.tostao.tostao_api.membership.MembershipScenario.accept;
import static com.tostao.tostao_api.membership.MembershipScenario.changeRole;
import static com.tostao.tostao_api.membership.MembershipScenario.invite;
import static com.tostao.tostao_api.membership.MembershipScenario.listInvitations;
import static com.tostao.tostao_api.membership.MembershipScenario.listMembers;
import static com.tostao.tostao_api.membership.MembershipScenario.revokeInvitation;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.membership.dto.AcceptInvitationRequest;
import com.tostao.tostao_api.membership.dto.InvitationCreatedResponse;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * SC-003: o código do convite aparece só na resposta que cria o convite. Nenhuma listagem, consulta, mensagem de erro,
 * saída do processo nem coluna do banco o contém, e a impressão que o banco guarda não permite reconstruí-lo.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class InvitationSecretTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private static String sha256(String code) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private record Case(MembershipScenario s, Fixture guest, String email, String code, String hash, String invitationId) {
	}

	/** Um convite pronto: o dono convida a pessoa e o código volta uma só vez. */
	private Case invited() throws Exception {
		MembershipScenario s = MembershipScenario.create(fixtures());
		UUID user = fixtures().newUser();
		String email = owner.client().sql("select email from users where id = :id").param("id", user).query(String.class).single();
		Fixture guest = new Fixture(UUID.randomUUID(), user, fixtures().tokenOf(user));
		String created = body(invite(mvc, s.owner(), s.workspace(), "{\"email\":\"" + email + "\",\"role\":\"EDITOR\"}"));
		String code = JsonPath.read(created, "$.code");
		return new Case(s, guest, email, code, sha256(code), JsonPath.read(created, "$.id"));
	}

	/** O que nenhuma resposta (além da criação) pode conter: o código e a impressão dele. */
	private static void assertNoSecret(String where, String json, Case c) {
		assertThat(json).as(where + ": o código").doesNotContain(c.code());
		assertThat(json).as(where + ": a impressão do código").doesNotContain(c.hash());
		// a chave JSON "code": só existe na resposta de criação (um erro de validação pode citar o nome do campo, sem o valor)
		assertThat(json).as(where + ": a chave code").doesNotContain("\"code\":");
		assertThat(json).as(where + ": o campo codeHash").doesNotContainIgnoringCase("codeHash").doesNotContainIgnoringCase("code_hash");
	}

	@Test
	void theCodeIsInTheCreationResponseAndInNoOtherResponseOfTheFeature() throws Exception {
		Case c = invited();
		List<String> others = new ArrayList<>();

		others.add(body(listInvitations(mvc, c.s().owner(), c.s().workspace())));
		others.add(body(listMembers(mvc, c.s().owner(), c.s().workspace())));
		others.add(body(listMembers(mvc, c.s().editor(), c.s().workspace())));
		others.add(body(mvc.perform(get("/api/v1/me").cookie(session(c.s().owner().token())))));
		others.add(body(changeRole(mvc, c.s().owner(), c.s().workspace(), c.s().editor().userId(), "VIEWER", true)));
		// as respostas de erro de cada rota, com e sem permissão
		others.add(body(invite(mvc, c.s().owner(), c.s().workspace(), "{\"email\":\"" + c.email() + "\",\"role\":\"OWNER\"}")));
		others.add(body(invite(mvc, c.s().viewer(), c.s().workspace(), "{\"email\":\"x@exemplo.com\",\"role\":\"EDITOR\"}")));
		others.add(body(listInvitations(mvc, c.s().viewer(), c.s().workspace())));
		others.add(body(revokeInvitation(mvc, c.s().owner(), c.s().workspace(), UUID.randomUUID())));
		others.add(body(accept(mvc, c.s().outsider(), "{\"code\":\"" + c.code() + "\"}")));
		others.add(body(accept(mvc, c.s().outsider(), "{\"code\":\"\"}")));
		// o aceite que dá certo traz o workspace e o papel, não o código
		String accepted = body(accept(mvc, c.guest(), "{\"code\":\"" + c.code() + "\"}"));
		others.add(accepted);
		others.add(body(accept(mvc, c.guest(), "{\"code\":\"" + c.code() + "\"}")));
		others.add(body(listInvitations(mvc, c.s().owner(), c.s().workspace())));

		for (int i = 0; i < others.size(); i++) {
			assertNoSecret("resposta " + i, others.get(i), c);
		}
		assertThat((String) JsonPath.read(accepted, "$.role")).isEqualTo("EDITOR");
	}

	@Test
	void theCodeNeverReachesTheProcessOutput(CapturedOutput output) throws Exception {
		Case c = invited();

		listInvitations(mvc, c.s().owner(), c.s().workspace());
		accept(mvc, c.s().outsider(), "{\"code\":\"" + c.code() + "\"}");
		accept(mvc, c.guest(), "{\"code\":\"" + c.code() + "\"}");
		accept(mvc, c.guest(), "{\"code\":\"" + c.code() + "\"}");
		String secondCode = JsonPath.read(body(invite(mvc, c.s().owner(), c.s().workspace(), "{\"email\":\"" + UUID.randomUUID() + "@exemplo.com\",\"role\":\"VIEWER\"}")), "$.code");
		revokeInvitation(mvc, c.s().owner(), c.s().workspace(), JsonPath.<String>read(body(listInvitations(mvc, c.s().owner(), c.s().workspace())), "$[0].id"));

		assertThat(output.getAll()).doesNotContain(c.code()).doesNotContain(secondCode).doesNotContain(c.hash());
	}

	@Test
	void theDatabaseNeverKeepsTheCodeAndTheHashDoesNotRevealIt() throws Exception {
		Case c = invited();

		String row = owner.client().sql("select x::text from invitations x where x.id = cast(:id as uuid)").param("id", c.invitationId())
				.query(String.class).single();

		assertThat(row).doesNotContain(c.code());
		assertThat(row).contains(c.hash());
		// a impressão é só o SHA-256: não é o código, não contém pedaço dele e tem o tamanho certo
		assertThat(c.hash()).hasSize(64).isNotEqualTo(c.code()).doesNotContain(c.code().substring(0, 10));
		// e o código não está em nenhuma outra tabela de membros nem de usuários
		assertThat(owner.client().sql("select count(*) from workspace_members x where x::text like '%' || :c || '%'").param("c", c.code()).query(Long.class).single()).isZero();
		assertThat(owner.client().sql("select count(*) from users x where x::text like '%' || :c || '%'").param("c", c.code()).query(Long.class).single()).isZero();
	}

	@Test
	void theObjectsThatCarryTheCodeMaskItInTheirTextForm() {
		String code = "segredo-de-teste-que-nao-pode-vazar";

		assertThat(new InvitationCreatedResponse(UUID.randomUUID(), "a@x.com", "EDITOR", UUID.randomUUID(), Instant.now(), Instant.now(), code).toString())
				.doesNotContain(code).contains("***");
		assertThat(new AcceptInvitationRequest(code).toString()).doesNotContain(code).contains("***");
	}

	@Test
	void theCodeIsOnlyValidWithTheHashOfTheOneThatWasGiven() throws Exception {
		Case c = invited();

		// um código quase igual (um caractere trocado) não aceita o convite
		String almost = c.code().substring(0, 42) + (c.code().charAt(42) == 'A' ? 'B' : 'A');
		accept(mvc, c.guest(), "{\"code\":\"" + almost + "\"}").andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
		accept(mvc, c.guest(), "{\"code\":\"" + c.code() + "\"}").andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
	}

}
