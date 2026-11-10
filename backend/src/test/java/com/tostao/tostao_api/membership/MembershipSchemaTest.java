package com.tostao.tostao_api.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;
import com.tostao.tostao_api.workspace.TenantSession;

/**
 * A {@code V12} vale no banco: os convites têm restrições próprias e só o dono os enxerga e mexe; o vínculo muda e termina
 * só sob as regras do dono (ou da própria saída); um workspace tem um só OWNER; e os membros veem nome e e-mail uns dos
 * outros sem nunca alcançar hash de senha.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MembershipSchemaTest {

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

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	/** Um workspace com o dono, um editor e um leitor. */
	private record World(Fixture owner, Fixture editor, Fixture viewer) {

		UUID workspace() {
			return owner.workspaceId();
		}
	}

	private World world() {
		WorkspaceFixtures f = fixtures();
		Fixture o = f.workspaceWithOwner();
		return new World(o, f.addMember(o.workspaceId(), "EDITOR"), f.addMember(o.workspaceId(), "VIEWER"));
	}

	private static String hash() {
		return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 64);
	}

	/** Insere um convite pela conexão do dono (sem RLS). */
	private UUID invitation(UUID workspace, UUID createdBy, String email, String role, String codeHash) {
		return owner.client().sql("""
				insert into invitations (workspace_id, email, role, code_hash, created_by, expires_at)
				values (:w, :e, :r, :h, :u, now() + interval '7 days') returning id""")
				.param("w", workspace).param("e", email).param("r", role).param("h", codeHash).param("u", createdBy)
				.query(UUID.class).single();
	}

	private UUID invitation(World w, String email) {
		return invitation(w.workspace(), w.owner().userId(), email, "EDITOR", hash());
	}

	private <T> T asApp(Fixture who, UUID workspace, Function<JdbcClient, T> work) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(who.userId(), workspace);
			return work.apply(appJdbc);
		});
	}

	/** Como asApp, para atualizações que devolvem a contagem de linhas (evita a ambiguidade de assertThat). */
	private int asAppInt(Fixture who, UUID workspace, Function<JdbcClient, Integer> work) {
		return asApp(who, workspace, work);
	}

	private long countAsApp(Fixture who, UUID workspace, String sql) {
		return asApp(who, workspace, db -> db.sql(sql).query(Long.class).single());
	}

	// ---- restrições de invitations ----------------------------------------------------------------------------

	@Test
	void theDatabaseRefusesInvalidInvitations() {
		World w = world();
		UUID ws = w.workspace();
		UUID u = w.owner().userId();

		assertThatThrownBy(() -> invitation(ws, u, "ana@x.com", "OWNER", hash())).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> invitation(ws, u, "ana@x.com", "OUTRO", hash())).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> invitation(ws, u, "Ana@x.com", "EDITOR", hash())).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> invitation(ws, u, " ana@x.com", "EDITOR", hash())).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> invitation(ws, u, "ab", "EDITOR", hash())).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> invitation(ws, u, "a".repeat(250) + "@x.com", "EDITOR", hash()))
				.isInstanceOf(DataIntegrityViolationException.class);
		for (String bad : new String[] { "ABC".repeat(21) + "a", "g".repeat(64), "a".repeat(63), "a".repeat(65), "" }) {
			assertThatThrownBy(() -> invitation(ws, u, "ana@x.com", "EDITOR", bad)).as(bad).isInstanceOf(Exception.class);
		}
		// os limites de cada ponta são aceitos
		assertThat(invitation(ws, u, "abc", "VIEWER", hash())).isNotNull();
		assertThat(invitation(ws, u, "a".repeat(248) + "@x.com", "EDITOR", hash())).isNotNull();
	}

	@Test
	void theExpiryMustBeAfterTheCreationAndTheAcceptedColumnsComeInPair() {
		World w = world();
		UUID id = invitation(w, "ana@x.com");

		assertThatThrownBy(() -> owner.client().sql("update invitations set expires_at = created_at - interval '1 second' where id = :id")
				.param("id", id).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> owner.client().sql("update invitations set accepted_at = now() where id = :id").param("id", id).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> owner.client().sql("update invitations set accepted_by = :u where id = :id")
				.param("u", w.editor().userId()).param("id", id).update()).isInstanceOf(DataIntegrityViolationException.class);
		// aceito e revogado ao mesmo tempo nunca
		owner.client().sql("update invitations set accepted_at = now(), accepted_by = :u where id = :id")
				.param("u", w.editor().userId()).param("id", id).update();
		assertThatThrownBy(() -> owner.client().sql("update invitations set revoked_at = now() where id = :id").param("id", id).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theCodeHashIsUnique() {
		World w = world();
		String hash = hash();
		invitation(w.workspace(), w.owner().userId(), "ana@x.com", "EDITOR", hash);

		assertThatThrownBy(() -> invitation(w.workspace(), w.owner().userId(), "bia@x.com", "VIEWER", hash))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void thereIsOnePendingInvitationPerEmailAndWorkspace() {
		World w = world();
		World other = world();
		UUID first = invitation(w, "ana@x.com");

		assertThatThrownBy(() -> invitation(w, "ana@x.com")).isInstanceOf(DataIntegrityViolationException.class);
		// o mesmo e-mail em outro workspace e outro e-mail no mesmo são aceitos
		invitation(other, "ana@x.com");
		invitation(w, "bia@x.com");
		// depois de revogado, o e-mail pode ser convidado de novo
		owner.client().sql("update invitations set revoked_at = now() where id = :id").param("id", first).update();
		UUID second = invitation(w, "ana@x.com");
		// e depois de aceito também
		owner.client().sql("update invitations set accepted_at = now(), accepted_by = :u where id = :id")
				.param("u", w.viewer().userId()).param("id", second).update();
		assertThat(invitation(w, "ana@x.com")).isNotNull();
	}

	// ---- um só OWNER ------------------------------------------------------------------------------------------

	@Test
	void aWorkspaceHasASingleOwner() {
		World w = world();
		UUID stranger = fixtures().newUser();

		assertThatThrownBy(() -> owner.client().sql("insert into workspace_members (workspace_id, user_id, role) values (:w, :u, 'OWNER')")
				.param("w", w.workspace()).param("u", stranger).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> owner.client().sql("update workspace_members set role = 'OWNER' where workspace_id = :w and user_id = :u")
				.param("w", w.workspace()).param("u", w.editor().userId()).update()).isInstanceOf(DataIntegrityViolationException.class);
		// outro workspace pode ter o seu dono
		assertThat(world().owner()).isNotNull();
	}

	// ---- quem enxerga e mexe em invitations --------------------------------------------------------------------

	@Test
	void onlyTheOwnerReadsInsertsAndRevokesInvitationsOfItsWorkspace() {
		World w = world();
		UUID mine = invitation(w, "ana@x.com");

		assertThat(countAsApp(w.owner(), w.workspace(), "select count(*) from invitations")).isEqualTo(1L);
		assertThat(countAsApp(w.editor(), w.workspace(), "select count(*) from invitations")).as("EDITOR não vê nada").isZero();
		assertThat(countAsApp(w.viewer(), w.workspace(), "select count(*) from invitations")).as("VIEWER não vê nada").isZero();

		String insert = """
				insert into invitations (workspace_id, email, role, code_hash, created_by, expires_at)
				values (:w, 'bia@x.com', 'VIEWER', :h, :u, now() + interval '7 days')""";
		assertThat(asAppInt(w.owner(), w.workspace(), db -> db.sql(insert).param("w", w.workspace()).param("h", hash())
				.param("u", w.owner().userId()).update())).isEqualTo(1);
		for (Fixture who : new Fixture[] { w.editor(), w.viewer() }) {
			assertThatThrownBy(() -> asApp(who, w.workspace(), db -> db.sql(insert).param("w", w.workspace()).param("h", hash())
					.param("u", who.userId()).update())).rootCause().hasMessageContaining("row-level security");
		}
		assertThat(asAppInt(w.owner(), w.workspace(), db -> db.sql("update invitations set revoked_at = now() where id = :id")
				.param("id", mine).update())).isEqualTo(1);
		for (Fixture who : new Fixture[] { w.editor(), w.viewer() }) {
			assertThat(asAppInt(who, w.workspace(), db -> db.sql("update invitations set revoked_at = now() where revoked_at is null").update()))
					.isZero();
		}
	}

	@Test
	void nobodyInsertsOnBehalfOfAnotherPersonOrWorkspace() {
		World a = world();
		World b = world();
		String insert = """
				insert into invitations (workspace_id, email, role, code_hash, created_by, expires_at)
				values (:w, 'x@x.com', 'EDITOR', :h, :u, now() + interval '7 days')""";

		// created_by é sempre a pessoa da sessão
		assertThatThrownBy(() -> asApp(a.owner(), a.workspace(), db -> db.sql(insert).param("w", a.workspace()).param("h", hash())
				.param("u", a.editor().userId()).update())).rootCause().hasMessageContaining("row-level security");
		// e o workspace do contexto é o único em que se insere
		assertThatThrownBy(() -> asApp(a.owner(), a.workspace(), db -> db.sql(insert).param("w", b.workspace()).param("h", hash())
				.param("u", a.owner().userId()).update())).rootCause().hasMessageContaining("row-level security");
	}

	@Test
	void theApplicationRoleNeverEditsOrDeletesAnInvitationBeyondRevoking() {
		World w = world();
		UUID id = invitation(w, "ana@x.com");

		for (String change : new String[] { "accepted_at = now()", "accepted_by = null", "email = 'outra@x.com'", "role = 'VIEWER'",
				"code_hash = repeat('a', 64)", "expires_at = now() + interval '99 days'", "workspace_id = gen_random_uuid()",
				"created_by = gen_random_uuid()" }) {
			assertThatThrownBy(() -> asApp(w.owner(), w.workspace(), db -> db.sql("update invitations set " + change + " where id = :id")
					.param("id", id).update())).as(change).rootCause().hasMessageContaining("permission denied");
		}
		assertThatThrownBy(() -> asApp(w.owner(), w.workspace(), db -> db.sql("delete from invitations where id = :id").param("id", id).update()))
				.rootCause().hasMessageContaining("permission denied");
	}

	@Test
	void anotherWorkspaceAndNoContextReachNoInvitation() {
		World a = world();
		World b = world();
		UUID id = invitation(b, "ana@x.com");

		assertThat(countAsApp(a.owner(), a.workspace(), "select count(*) from invitations")).isZero();
		assertThat(asAppInt(a.owner(), a.workspace(), db -> db.sql("update invitations set revoked_at = now() where id = :id").param("id", id).update()))
				.isZero();
		long noContext = new TransactionTemplate(transactionManager)
				.execute(status -> appJdbc.sql("select count(*) from invitations").query(Long.class).single());
		assertThat(noContext).isZero();
	}

	// ---- vínculos ---------------------------------------------------------------------------------------------

	private String roleOf(World w, Fixture who) {
		return owner.client().sql("select role from workspace_members where workspace_id = :w and user_id = :u")
				.param("w", w.workspace()).param("u", who.userId()).query(String.class).optional().orElse("(removido)");
	}

	private int setRole(World w, Fixture caller, Fixture target, String role) {
		return asApp(caller, w.workspace(), db -> db.sql("update workspace_members set role = :r where workspace_id = :w and user_id = :u")
				.param("r", role).param("w", w.workspace()).param("u", target.userId()).update());
	}

	@Test
	void theOwnerChangesTheRoleOfEditorsAndViewersOnlyBetweenThoseTwo() {
		World w = world();

		assertThat(setRole(w, w.owner(), w.editor(), "VIEWER")).isEqualTo(1);
		assertThat(roleOf(w, w.editor())).isEqualTo("VIEWER");
		assertThat(setRole(w, w.owner(), w.viewer(), "EDITOR")).isEqualTo(1);
		assertThat(roleOf(w, w.viewer())).isEqualTo("EDITOR");
		// nunca para OWNER (o CHECK da política recusa)
		assertThatThrownBy(() -> setRole(w, w.owner(), w.editor(), "OWNER")).rootCause().hasMessageContaining("row-level security");
		// nem o papel do dono, nem o próprio papel
		assertThat(setRole(w, w.owner(), w.owner(), "VIEWER")).isZero();
		assertThat(roleOf(w, w.owner())).isEqualTo("OWNER");
	}

	@Test
	void editorsAndViewersChangeNoRole() {
		World w = world();

		for (Fixture who : new Fixture[] { w.editor(), w.viewer() }) {
			assertThat(setRole(w, who, w.viewer(), "EDITOR")).as("outro membro").isZero();
			assertThat(setRole(w, who, who, "EDITOR")).as("o próprio papel").isZero();
			assertThat(setRole(w, who, w.owner(), "VIEWER")).as("o dono").isZero();
		}
		assertThat(roleOf(w, w.owner())).isEqualTo("OWNER");
		assertThat(roleOf(w, w.editor())).isEqualTo("EDITOR");
		assertThat(roleOf(w, w.viewer())).isEqualTo("VIEWER");
	}

	@Test
	void noOneInsertsAMembershipThroughTheApplicationRole() {
		World w = world();
		UUID stranger = fixtures().newUser();

		assertThatThrownBy(() -> asApp(w.owner(), w.workspace(), db -> db
				.sql("insert into workspace_members (workspace_id, user_id, role) values (:w, :u, 'VIEWER')")
				.param("w", w.workspace()).param("u", stranger).update())).rootCause().hasMessageContaining("permission denied");
	}

	private int remove(World w, Fixture caller, Fixture target) {
		return asApp(caller, w.workspace(), db -> db.sql("delete from workspace_members where workspace_id = :w and user_id = :u")
				.param("w", w.workspace()).param("u", target.userId()).update());
	}

	@Test
	void theOwnerRemovesAnyoneButItselfAndMembersOnlyLeave() {
		World w = world();

		// um membro que não é dono não remove outro
		assertThat(remove(w, w.viewer(), w.editor())).isZero();
		assertThat(remove(w, w.editor(), w.viewer())).isZero();
		assertThat(remove(w, w.editor(), w.owner())).isZero();
		assertThat(roleOf(w, w.editor())).isEqualTo("EDITOR");
		// o dono nunca é removido, nem por ele mesmo
		assertThat(remove(w, w.owner(), w.owner())).isZero();
		assertThat(roleOf(w, w.owner())).isEqualTo("OWNER");
		// cada membro sai por conta própria
		assertThat(remove(w, w.viewer(), w.viewer())).isEqualTo(1);
		assertThat(roleOf(w, w.viewer())).isEqualTo("(removido)");
		// e o dono remove quem sobrou
		assertThat(remove(w, w.owner(), w.editor())).isEqualTo(1);
		assertThat(roleOf(w, w.editor())).isEqualTo("(removido)");
	}

	@Test
	void anotherWorkspacesOwnerRemovesAndChangesNothingHere() {
		World a = world();
		World b = world();

		assertThat(asAppInt(b.owner(), b.workspace(), db -> db.sql("delete from workspace_members where user_id = :u").param("u", a.editor().userId()).update()))
				.isZero();
		assertThat(asAppInt(b.owner(), b.workspace(), db -> db.sql("update workspace_members set role = 'VIEWER' where user_id = :u")
				.param("u", a.editor().userId()).update())).isZero();
		assertThat(roleOf(a, a.editor())).isEqualTo("EDITOR");
	}

	// ---- usuários ---------------------------------------------------------------------------------------------

	@Test
	void membersReadTheNameAndEmailOfCoMembersAndNeverTheHashOrTheSessionVersion() {
		World w = world();
		World other = world();

		List<UUID> seen = asApp(w.viewer(), w.workspace(), db -> db.sql("select id from users").query(UUID.class).list());

		assertThat(seen).containsExactlyInAnyOrder(w.owner().userId(), w.editor().userId(), w.viewer().userId());
		assertThat(seen).doesNotContain(other.owner().userId());
		String contact = asApp(w.viewer(), w.workspace(), db -> db.sql("select name || '|' || email from users where id = :id")
				.param("id", w.owner().userId()).query(String.class).single());
		assertThat(contact).contains("@");
		for (String secret : new String[] { "password_hash", "session_version" }) {
			assertThatThrownBy(() -> asApp(w.viewer(), w.workspace(), db -> db.sql("select " + secret + " from users").query(String.class).list()))
					.as(secret).rootCause().hasMessageContaining("permission denied");
		}
	}

	@Test
	void withoutAWorkspaceContextAPersonStillOnlySeesTheirOwnUserRow() {
		World w = world();

		List<UUID> seen = new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterUser(w.viewer().userId());
			return appJdbc.sql("select id from users").query(UUID.class).list();
		});

		assertThat(seen).containsExactly(w.viewer().userId());
	}

}
