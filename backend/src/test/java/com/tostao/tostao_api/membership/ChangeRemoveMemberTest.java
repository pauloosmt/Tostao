package com.tostao.tostao_api.membership;

import static com.tostao.tostao_api.membership.MembershipScenario.changeRole;
import static com.tostao.tostao_api.membership.MembershipScenario.listMembers;
import static com.tostao.tostao_api.membership.MembershipScenario.membersFingerprint;
import static com.tostao.tostao_api.membership.MembershipScenario.removeMember;
import static com.tostao.tostao_api.membership.MembershipScenario.roleOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** História 3: o dono muda papéis e remove membros; quem não é dono sai. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ChangeRemoveMemberTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private MembershipScenario scenario() {
		return MembershipScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	// ---- mudar o papel ----------------------------------------------------------------------------------------

	@Test
	void theOwnerSwitchesEditorsAndViewersBothWays() throws Exception {
		MembershipScenario s = scenario();

		changeRole(mvc, s.owner(), s.workspace(), s.editor().userId(), "VIEWER", true).andExpect(status().isOk())
				.andExpect(jsonPath("$.userId").value(s.editor().userId().toString())).andExpect(jsonPath("$.role").value("VIEWER"));
		assertThat(roleOf(owner, s.workspace(), s.editor().userId())).isEqualTo("VIEWER");
		changeRole(mvc, s.owner(), s.workspace(), s.viewer().userId(), "EDITOR", true).andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("EDITOR"));
		assertThat(roleOf(owner, s.workspace(), s.viewer().userId())).isEqualTo("EDITOR");
		// e a lista reflete
		listMembers(mvc, s.owner(), s.workspace()).andExpect(jsonPath("$[1].role").value("VIEWER"))
				.andExpect(jsonPath("$[2].role").value("EDITOR"));
	}

	@Test
	void askingForTheRoleAMemberAlreadyHasIsAnAnswerWithoutEffect() throws Exception {
		MembershipScenario s = scenario();
		String before = membersFingerprint(owner, s.workspace());

		changeRole(mvc, s.owner(), s.workspace(), s.editor().userId(), "EDITOR", true).andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("EDITOR"));

		assertThat(membersFingerprint(owner, s.workspace())).isEqualTo(before);
	}

	@Test
	void theOwnerRoleNeverChangesAndNobodyBecomesOwner() throws Exception {
		MembershipScenario s = scenario();
		String before = membersFingerprint(owner, s.workspace());

		// o próprio dono e o papel do dono: conflito
		changeRole(mvc, s.owner(), s.workspace(), s.owner().userId(), "VIEWER", true).andExpect(status().isConflict());
		// dar o papel OWNER: 400 no campo role
		changeRole(mvc, s.owner(), s.workspace(), s.editor().userId(), "OWNER", true).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("role"));

		assertThat(membersFingerprint(owner, s.workspace())).isEqualTo(before);
		assertThat(roleOf(owner, s.workspace(), s.owner().userId())).isEqualTo("OWNER");
	}

	@Test
	void anInvalidRoleIsRefusedOnItsField() throws Exception {
		MembershipScenario s = scenario();
		String before = membersFingerprint(owner, s.workspace());

		for (String role : new String[] { "\"OUTRO\"", "\"\"", "\"editor\"", "null", "1", "{}" }) {
			changeRole(mvc, s.owner(), s.workspace(), s.editor().userId(), "{\"role\":" + role + "}").andExpect(status().isBadRequest());
		}
		changeRole(mvc, s.owner(), s.workspace(), s.editor().userId(), "{}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("role"));
		changeRole(mvc, s.owner(), s.workspace(), s.editor().userId(), "{\"role\":\"VIEWER\",\"userId\":\"" + UUID.randomUUID() + "\"}")
				.andExpect(status().isBadRequest());

		assertThat(membersFingerprint(owner, s.workspace())).isEqualTo(before);
	}

	@Test
	void editorsAndViewersCannotChangeAnyRole() throws Exception {
		MembershipScenario s = scenario();
		String before = membersFingerprint(owner, s.workspace());

		for (Fixture who : new Fixture[] { s.editor(), s.viewer() }) {
			changeRole(mvc, who, s.workspace(), s.viewer().userId(), "EDITOR", true).andExpect(status().isForbidden());
			changeRole(mvc, who, s.workspace(), who.userId(), "EDITOR", true).andExpect(status().isForbidden());
			changeRole(mvc, who, s.workspace(), s.owner().userId(), "VIEWER", true).andExpect(status().isForbidden());
		}

		assertThat(membersFingerprint(owner, s.workspace())).isEqualTo(before);
	}

	@Test
	void anUnknownForeignOrMalformedMemberIsNotFoundOnEveryOperation() throws Exception {
		MembershipScenario s = scenario();
		String before = membersFingerprint(owner, s.workspace());

		for (Object target : new Object[] { UUID.randomUUID(), "abc", s.outsider().userId() }) {
			changeRole(mvc, s.owner(), s.workspace(), target, "VIEWER", true).andExpect(status().isNotFound());
			removeMember(mvc, s.owner(), s.workspace(), target).andExpect(status().isNotFound());
		}

		assertThat(membersFingerprint(owner, s.workspace())).isEqualTo(before);
	}

	// ---- remover e sair ---------------------------------------------------------------------------------------

	@Test
	void theOwnerRemovesAMemberAndRemovingTwiceIsNotFound() throws Exception {
		MembershipScenario s = scenario();

		removeMember(mvc, s.owner(), s.workspace(), s.editor().userId()).andExpect(status().isNoContent());

		assertThat(roleOf(owner, s.workspace(), s.editor().userId())).isNull();
		listMembers(mvc, s.owner(), s.workspace()).andExpect(jsonPath("$.length()").value(2));
		removeMember(mvc, s.owner(), s.workspace(), s.editor().userId()).andExpect(status().isNotFound());
	}

	@Test
	void theOwnerNeverLeavesAndIsNeverRemoved() throws Exception {
		MembershipScenario s = scenario();
		String before = membersFingerprint(owner, s.workspace());

		removeMember(mvc, s.owner(), s.workspace(), s.owner().userId()).andExpect(status().isConflict());

		assertThat(membersFingerprint(owner, s.workspace())).isEqualTo(before);
		// outro membro tentando remover o dono é recusado por falta de permissão
		removeMember(mvc, s.editor(), s.workspace(), s.owner().userId()).andExpect(status().isForbidden());
		removeMember(mvc, s.viewer(), s.workspace(), s.owner().userId()).andExpect(status().isForbidden());
		assertThat(membersFingerprint(owner, s.workspace())).isEqualTo(before);
	}

	@Test
	void editorsAndViewersLeaveOnTheirOwnAndCannotRemoveAnyoneElse() throws Exception {
		MembershipScenario s = scenario();
		String before = membersFingerprint(owner, s.workspace());

		removeMember(mvc, s.editor(), s.workspace(), s.viewer().userId()).andExpect(status().isForbidden());
		removeMember(mvc, s.viewer(), s.workspace(), s.editor().userId()).andExpect(status().isForbidden());
		assertThat(membersFingerprint(owner, s.workspace())).isEqualTo(before);

		removeMember(mvc, s.editor(), s.workspace(), s.editor().userId()).andExpect(status().isNoContent());
		removeMember(mvc, s.viewer(), s.workspace(), s.viewer().userId()).andExpect(status().isNoContent());

		assertThat(roleOf(owner, s.workspace(), s.editor().userId())).isNull();
		assertThat(roleOf(owner, s.workspace(), s.viewer().userId())).isNull();
		assertThat(roleOf(owner, s.workspace(), s.owner().userId())).isEqualTo("OWNER");
	}

	@Test
	void aMemberWhoLeftNoLongerReachesTheWorkspace() throws Exception {
		MembershipScenario s = scenario();
		removeMember(mvc, s.viewer(), s.workspace(), s.viewer().userId()).andExpect(status().isNoContent());

		// na hora: o workspace vira "não encontrado" para quem saiu, em qualquer operação
		listMembers(mvc, s.viewer(), s.workspace()).andExpect(status().isNotFound());
		removeMember(mvc, s.viewer(), s.workspace(), s.viewer().userId()).andExpect(status().isNotFound());
		changeRole(mvc, s.viewer(), s.workspace(), s.editor().userId(), "VIEWER", true).andExpect(status().isNotFound());
	}

	@Test
	void whatAMemberCreatedStaysInTheWorkspaceWithTheAuthorIntactAfterLeavingOrBeingRemoved() throws Exception {
		MembershipScenario s = scenario();
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		UUID account = f.insertAccount(s.workspace(), "Corrente", "CHECKING", 0, false);
		UUID category = f.insertCategory(s.workspace(), null, "Mercado", "EXPENSE", false);
		UUID byEditor = f.insertLedgerTransaction(s.workspace(), account, s.editor().userId(), category, "EXPENSE", 5_000,
				LocalDate.of(2026, 11, 5), "Do editor", false);
		UUID byViewer = f.insertLedgerTransaction(s.workspace(), account, s.viewer().userId(), category, "EXPENSE", 700,
				LocalDate.of(2026, 11, 6), "Do leitor", false);

		removeMember(mvc, s.owner(), s.workspace(), s.editor().userId()).andExpect(status().isNoContent());
		removeMember(mvc, s.viewer(), s.workspace(), s.viewer().userId()).andExpect(status().isNoContent());

		assertThat(owner.client().sql("select created_by from transactions where id = :id").param("id", byEditor).query(UUID.class).single())
				.isEqualTo(s.editor().userId());
		assertThat(owner.client().sql("select created_by from transactions where id = :id").param("id", byViewer).query(UUID.class).single())
				.isEqualTo(s.viewer().userId());
		assertThat(owner.client().sql("select count(*) from transactions where workspace_id = :w and deleted_at is null")
				.param("w", s.workspace()).query(Long.class).single()).isEqualTo(2L);
		// e os usuários continuam existindo (só o vínculo terminou)
		assertThat(owner.client().sql("select count(*) from users where id in (:a, :b)").param("a", s.editor().userId())
				.param("b", s.viewer().userId()).query(Long.class).single()).isEqualTo(2L);
	}

	@Test
	void theResponsesAreNeverCached() throws Exception {
		MembershipScenario s = scenario();

		changeRole(mvc, s.owner(), s.workspace(), s.editor().userId(), "VIEWER", true).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")));
		removeMember(mvc, s.owner(), s.workspace(), s.editor().userId()).andExpect(status().isNoContent())
				.andExpect(header().string("Cache-Control", containsString("no-store")));
	}

}
