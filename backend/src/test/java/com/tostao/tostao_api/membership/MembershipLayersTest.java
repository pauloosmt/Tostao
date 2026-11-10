package com.tostao.tostao_api.membership;

import static com.tostao.tostao_api.membership.MembershipScenario.invitationsFingerprint;
import static com.tostao.tostao_api.membership.MembershipScenario.membersFingerprint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.common.error.InvalidInvitationException;
import com.tostao.tostao_api.common.error.InvitationNotFoundException;
import com.tostao.tostao_api.common.error.MemberNotFoundException;
import com.tostao.tostao_api.membership.dto.ChangeRoleRequest;
import com.tostao.tostao_api.membership.dto.CreateInvitationRequest;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;
import com.tostao.tostao_api.workspace.TenantSession;

/**
 * As duas camadas de isolamento de membros e convites, uma de cada vez (SC-008). Camada de dados: o SQL roda como
 * {@code tostao_app} <b>sem filtro de workspace</b>, e só o RLS barra. Camada de serviço: o serviço roda com a conexão
 * do dono (RLS neutralizado) e o filtro explícito por {@code workspace_id} isola sozinho.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MembershipLayersTest {

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	JdbcClient appJdbc;
	@Autowired
	TenantSession tenant;
	@Autowired
	PlatformTransactionManager transactionManager;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private static void actAs(Fixture who) {
		Jwt jwt = Jwt.withTokenValue("teste").header("alg", "none").subject(who.userId().toString()).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}

	private MembershipScenario scenario() {
		return MembershipScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	private static String sha256(String code) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
	}

	private UUID invitation(MembershipScenario s, String email) throws Exception {
		return owner.client().sql("""
				insert into invitations (workspace_id, email, role, code_hash, created_by, expires_at)
				values (:w, :e, 'EDITOR', :h, :u, now() + interval '7 days') returning id""")
				.param("w", s.workspace()).param("e", email).param("h", sha256(UUID.randomUUID().toString()))
				.param("u", s.owner().userId()).query(UUID.class).single();
	}

	private <T> T asApp(Fixture who, UUID workspace, Function<JdbcClient, T> work) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(who.userId(), workspace);
			return work.apply(appJdbc);
		});
	}

	private int asAppInt(Fixture who, UUID workspace, Function<JdbcClient, Integer> work) {
		return asApp(who, workspace, work);
	}

	// ---- camada de dados --------------------------------------------------------------------------------------

	@Test
	void theDatabaseLayerAloneOnlyReachesTheOwnWorkspace() throws Exception {
		MembershipScenario a = scenario();
		MembershipScenario b = scenario();
		invitation(a, "a@exemplo.com");
		UUID foreignInvitation = invitation(b, "b@exemplo.com");
		String bMembers = membersFingerprint(owner, b.workspace());

		// sem filtro de workspace de propósito: só o RLS protege
		long seen = asApp(a.owner(), a.workspace(), db -> db.sql("select count(*) from invitations").query(Long.class).single());
		int demoted = asAppInt(a.owner(), a.workspace(), db -> db.sql("update workspace_members set role = 'VIEWER' where role <> 'OWNER'").update());
		int revoked = asAppInt(a.owner(), a.workspace(), db -> db.sql("update invitations set revoked_at = now() where revoked_at is null").update());
		int removed = asAppInt(a.owner(), a.workspace(), db -> db.sql("delete from workspace_members where role <> 'OWNER'").update());

		assertThat(seen).as("só o convite de A").isEqualTo(1L);
		assertThat(demoted).as("editor e leitor de A").isEqualTo(2);
		assertThat(revoked).isEqualTo(1);
		assertThat(removed).as("os dois não-donos de A").isEqualTo(2);
		assertThat(membersFingerprint(owner, b.workspace())).as("B intacto").isEqualTo(bMembers);
		assertThat(owner.client().sql("select revoked_at is null from invitations where id = :id").param("id", foreignInvitation)
				.query(Boolean.class).single()).isTrue();
	}

	@Test
	void theDatabaseLayerAloneRefusesToInsertAnInvitationInAnotherWorkspace() throws Exception {
		MembershipScenario a = scenario();
		MembershipScenario b = scenario();
		String before = invitationsFingerprint(owner, b.workspace());

		assertThatThrownBy(() -> asApp(a.owner(), a.workspace(), db -> db.sql("""
				insert into invitations (workspace_id, email, role, code_hash, created_by, expires_at)
				values (:w, 'x@exemplo.com', 'EDITOR', :h, :u, now() + interval '7 days')""")
				.param("w", b.workspace()).param("h", UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""))
				.param("u", a.owner().userId()).update())).rootCause().hasMessageContaining("row-level security");

		assertThat(invitationsFingerprint(owner, b.workspace())).isEqualTo(before);
	}

	@Test
	void editorsViewersAndNoContextReachNothing() throws Exception {
		MembershipScenario a = scenario();
		invitation(a, "a@exemplo.com");
		String members = membersFingerprint(owner, a.workspace());
		String invitations = invitationsFingerprint(owner, a.workspace());

		for (Fixture who : new Fixture[] { a.editor(), a.viewer() }) {
			assertThat((long) asApp(who, a.workspace(), db -> db.sql("select count(*) from invitations").query(Long.class).single())).isZero();
			assertThat(asAppInt(who, a.workspace(), db -> db.sql("update invitations set revoked_at = now()").update())).isZero();
			assertThat(asAppInt(who, a.workspace(), db -> db.sql("update workspace_members set role = 'VIEWER' where role <> 'OWNER'").update())).isZero();
		}
		assertThat(asAppInt(a.editor(), a.workspace(), db -> db.sql("delete from workspace_members where user_id = :u").param("u", a.viewer().userId()).update())).isZero();
		long noContext = new TransactionTemplate(transactionManager).execute(status -> appJdbc.sql("select count(*) from invitations").query(Long.class).single());
		int noContextUpdate = new TransactionTemplate(transactionManager).execute(status -> appJdbc.sql("update workspace_members set role = 'VIEWER' where role <> 'OWNER'").update());

		assertThat(noContext).isZero();
		assertThat(noContextUpdate).isZero();
		assertThat(membersFingerprint(owner, a.workspace())).isEqualTo(members);
		assertThat(invitationsFingerprint(owner, a.workspace())).isEqualTo(invitations);
	}

	// ---- camada de serviço ------------------------------------------------------------------------------------

	@Test
	void theServiceFilterAloneIsolatesWorkspacesWithRlsBypassed() throws Exception {
		MemberService members = new MemberService(owner.client());
		InvitationService invitations = new InvitationService(owner.client());
		MembershipScenario a = scenario();
		MembershipScenario b = scenario();
		UUID foreignInvitation = invitation(b, "b@exemplo.com");
		invitation(a, "a@exemplo.com");
		String aBefore = membersFingerprint(owner, a.workspace()) + invitationsFingerprint(owner, a.workspace());
		String bBefore = membersFingerprint(owner, b.workspace()) + invitationsFingerprint(owner, b.workspace());
		actAs(a.owner());

		// membros e convites de B pelo workspace de A: não encontrados, e B intacto
		assertThatThrownBy(() -> members.changeRole(a.workspace(), b.editor().userId(), new ChangeRoleRequest("VIEWER"))).isInstanceOf(MemberNotFoundException.class);
		assertThatThrownBy(() -> members.remove(a.workspace(), b.editor().userId())).isInstanceOf(MemberNotFoundException.class);
		assertThatThrownBy(() -> invitations.revoke(a.workspace(), foreignInvitation)).isInstanceOf(InvitationNotFoundException.class);
		// as listas de A não trazem nada de B
		assertThat(members.list(a.workspace())).extracting(m -> m.userId()).containsExactlyInAnyOrder(a.owner().userId(), a.editor().userId(),
				a.viewer().userId());
		assertThat(invitations.list(a.workspace())).extracting(i -> i.email()).containsExactly("a@exemplo.com");

		assertThat(membersFingerprint(owner, b.workspace()) + invitationsFingerprint(owner, b.workspace())).isEqualTo(bBefore);
		assertThat(membersFingerprint(owner, a.workspace()) + invitationsFingerprint(owner, a.workspace())).isEqualTo(aBefore);
	}

	@Test
	void creatingAnInvitationWritesOnlyToTheGivenWorkspace() throws Exception {
		InvitationService invitations = new InvitationService(owner.client());
		MembershipScenario a = scenario();
		MembershipScenario b = scenario();
		String bBefore = invitationsFingerprint(owner, b.workspace());
		actAs(a.owner());

		invitations.create(a.workspace(), new CreateInvitationRequest("novo@exemplo.com", "EDITOR"));

		assertThat(owner.client().sql("select count(*) from invitations where workspace_id = :w").param("w", a.workspace()).query(Long.class).single()).isEqualTo(1L);
		assertThat(invitationsFingerprint(owner, b.workspace())).isEqualTo(bBefore);
	}

	@Test
	void theCodeOfAnotherWorkspacesInvitationGivesNothingToSomeoneWithADifferentEmail() throws Exception {
		InvitationService invitations = new InvitationService(owner.client());
		MembershipScenario a = scenario();
		MembershipScenario b = scenario();
		String code = "codigo-de-b-" + UUID.randomUUID();
		owner.client().sql("""
				insert into invitations (workspace_id, email, role, code_hash, created_by, expires_at)
				values (:w, 'so-para-outra-pessoa@exemplo.com', 'VIEWER', :h, :u, now() + interval '7 days')""")
				.param("w", b.workspace()).param("h", sha256(code)).param("u", b.owner().userId()).update();
		String bBefore = membersFingerprint(owner, b.workspace());
		actAs(a.owner());

		assertThatThrownBy(() -> invitations.accept(code)).isInstanceOf(InvalidInvitationException.class);

		assertThat(membersFingerprint(owner, b.workspace())).isEqualTo(bBefore);
		assertThat(MembershipScenario.roleOf(owner, b.workspace(), a.owner().userId())).isNull();
	}

}
