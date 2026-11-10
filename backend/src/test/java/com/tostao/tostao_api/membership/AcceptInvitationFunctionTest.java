package com.tostao.tostao_api.membership;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * A {@code V13} direto no banco: o aceite só funciona para o código certo, dentro da validade, para a conta com o e-mail
 * do convite, cria exatamente o vínculo do convite, respeita o limite de 20 membros e nunca tem efeito além disso.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AcceptInvitationFunctionTest {

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	JdbcClient appJdbc;
	@Autowired
	PlatformTransactionManager transactionManager;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private record Outcome(String outcome, UUID workspaceId, String workspaceName, String role) {
	}

	/** O SHA-256 do código em hexadecimal, calculado aqui de forma independente da classe de produção. */
	private static String sha256(String code) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
	}

	private String emailOf(UUID user) {
		return owner.client().sql("select email from users where id = :id").param("id", user).query(String.class).single();
	}

	/** Um convite do dono do workspace para o e-mail dado, com um código novo; devolve o código. */
	private String invite(Fixture dono, String email, String role) throws Exception {
		String code = UUID.randomUUID() + "-" + UUID.randomUUID();
		owner.client().sql("""
				insert into invitations (workspace_id, email, role, code_hash, created_by, expires_at)
				values (:w, :e, :r, :h, :u, now() + interval '7 days')""")
				.param("w", dono.workspaceId()).param("e", email).param("r", role).param("h", sha256(code))
				.param("u", dono.userId()).update();
		return code;
	}

	private Outcome accept(JdbcClient db, String code, UUID user) throws Exception {
		return db.sql("select outcome, workspace_id, workspace_name, role from accept_invitation(:h, :u)")
				.param("h", sha256(code)).param("u", user)
				.query((rs, i) -> new Outcome(rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)))
				.single();
	}

	private Outcome accept(String code, UUID user) throws Exception {
		return accept(owner.client(), code, user);
	}

	private String roleOf(UUID workspace, UUID user) {
		return owner.client().sql("select role from workspace_members where workspace_id = :w and user_id = :u")
				.param("w", workspace).param("u", user).query(String.class).optional().orElse(null);
	}

	private long members(UUID workspace) {
		return owner.client().sql("select count(*) from workspace_members where workspace_id = :w").param("w", workspace)
				.query(Long.class).single();
	}

	private String invitationState(UUID workspace) {
		return owner.client().sql("select coalesce(string_agg(email || ':' || (accepted_at is not null) || ':' || (revoked_at is not null), ',' order by email), '-') "
				+ "from invitations where workspace_id = :w").param("w", workspace).query(String.class).single();
	}

	// ---- aceitar ----------------------------------------------------------------------------------------------

	@Test
	void acceptingCreatesExactlyTheMembershipOfTheInvitationAndMarksItUsed() throws Exception {
		Fixture dono = fixtures().workspaceWithOwner();
		UUID guest = fixtures().newUser();
		String code = invite(dono, emailOf(guest), "EDITOR");
		long before = members(dono.workspaceId());

		Outcome result = accept(code, guest);

		assertThat(result.outcome()).isEqualTo("ACCEPTED");
		assertThat(result.workspaceId()).isEqualTo(dono.workspaceId());
		assertThat(result.workspaceName()).isEqualTo("Fixture");
		assertThat(result.role()).isEqualTo("EDITOR");
		assertThat(roleOf(dono.workspaceId(), guest)).isEqualTo("EDITOR");
		assertThat(members(dono.workspaceId())).isEqualTo(before + 1);
		assertThat(owner.client().sql("select accepted_by = :u and accepted_at is not null and revoked_at is null from invitations where workspace_id = :w")
				.param("u", guest).param("w", dono.workspaceId()).query(Boolean.class).single()).isTrue();
	}

	@Test
	void aViewerInvitationCreatesAViewer() throws Exception {
		Fixture dono = fixtures().workspaceWithOwner();
		UUID guest = fixtures().newUser();

		assertThat(accept(invite(dono, emailOf(guest), "VIEWER"), guest).role()).isEqualTo("VIEWER");
		assertThat(roleOf(dono.workspaceId(), guest)).isEqualTo("VIEWER");
	}

	@Test
	void theSameCodeWorksOnlyOnce() throws Exception {
		Fixture dono = fixtures().workspaceWithOwner();
		UUID guest = fixtures().newUser();
		String code = invite(dono, emailOf(guest), "EDITOR");

		assertThat(accept(code, guest).outcome()).isEqualTo("ACCEPTED");
		assertThat(accept(code, guest).outcome()).isEqualTo("INVALID");
		// e outra pessoa com o código já usado também
		assertThat(accept(code, fixtures().newUser()).outcome()).isEqualTo("INVALID");
		assertThat(members(dono.workspaceId())).isEqualTo(2L);
	}

	@Test
	void theEmailOfTheAccountIsComparedIgnoringCase() throws Exception {
		Fixture dono = fixtures().workspaceWithOwner();
		UUID guest = fixtures().newUser();
		String email = emailOf(guest);
		String code = invite(dono, email, "VIEWER");
		owner.client().sql("update users set email = upper(email) where id = :id").param("id", guest).update();

		assertThat(accept(code, guest).outcome()).isEqualTo("ACCEPTED");
	}

	// ---- INVALID: nada acontece -------------------------------------------------------------------------------

	@Test
	void everyKindOfBadCodeIsInvalidAndChangesNothing() throws Exception {
		Fixture dono = fixtures().workspaceWithOwner();
		UUID guest = fixtures().newUser();
		UUID other = fixtures().newUser();
		String revoked = invite(dono, emailOf(guest), "EDITOR");
		owner.client().sql("update invitations set revoked_at = now() where code_hash = :h").param("h", sha256(revoked)).update();
		String expired = invite(dono, "expirado-" + UUID.randomUUID() + "@x.com", "EDITOR");
		owner.client().sql("update invitations set expires_at = now() - interval '1 second', created_at = now() - interval '8 days' where code_hash = :h")
				.param("h", sha256(expired)).update();
		UUID expiredGuest = fixtures().newUser();
		owner.client().sql("update users set email = (select email from invitations where code_hash = :h) where id = :u")
				.param("h", sha256(expired)).param("u", expiredGuest).update();
		String forSomeoneElse = invite(dono, emailOf(other), "EDITOR");
		String state = invitationState(dono.workspaceId());
		long membersBefore = members(dono.workspaceId());

		assertThat(accept("codigo-que-nao-existe", guest).outcome()).as("inexistente").isEqualTo("INVALID");
		assertThat(accept("", guest).outcome()).as("vazio").isEqualTo("INVALID");
		assertThat(accept(revoked, guest).outcome()).as("revogado").isEqualTo("INVALID");
		assertThat(accept(expired, expiredGuest).outcome()).as("expirado").isEqualTo("INVALID");
		assertThat(accept(forSomeoneElse, guest).outcome()).as("de outro e-mail").isEqualTo("INVALID");
		assertThat(accept(forSomeoneElse, UUID.randomUUID()).outcome()).as("pessoa inexistente").isEqualTo("INVALID");

		assertThat(members(dono.workspaceId())).isEqualTo(membersBefore);
		assertThat(invitationState(dono.workspaceId())).as("nenhum convite mudou").isEqualTo(state);
		// e o convite de outro e-mail continua utilizável pela pessoa certa
		assertThat(accept(forSomeoneElse, other).outcome()).isEqualTo("ACCEPTED");
	}

	@Test
	void theInvalidAnswerCarriesNoWorkspaceData() throws Exception {
		Fixture dono = fixtures().workspaceWithOwner();
		UUID other = fixtures().newUser();
		String code = invite(dono, emailOf(fixtures().newUser()), "EDITOR");

		Outcome result = accept(code, other);

		assertThat(result).isEqualTo(new Outcome("INVALID", null, null, null));
	}

	// ---- ALREADY_MEMBER e FULL --------------------------------------------------------------------------------

	@Test
	void alreadyBeingAMemberDoesNotConsumeTheInvitation() throws Exception {
		Fixture dono = fixtures().workspaceWithOwner();
		Fixture editor = fixtures().addMember(dono.workspaceId(), "EDITOR");
		String code = invite(dono, emailOf(editor.userId()), "VIEWER");
		String state = invitationState(dono.workspaceId());

		assertThat(accept(code, editor.userId())).isEqualTo(new Outcome("ALREADY_MEMBER", null, null, null));

		assertThat(roleOf(dono.workspaceId(), editor.userId())).as("o papel não muda").isEqualTo("EDITOR");
		assertThat(invitationState(dono.workspaceId())).isEqualTo(state);
	}

	@Test
	void theTwentyMemberLimitIsNeverExceeded() throws Exception {
		Fixture dono = fixtures().workspaceWithOwner();
		for (int i = 0; i < 18; i++) {
			fixtures().addMember(dono.workspaceId(), "VIEWER");
		}
		assertThat(members(dono.workspaceId())).isEqualTo(19L);
		UUID twentieth = fixtures().newUser();
		UUID twentyFirst = fixtures().newUser();
		String c20 = invite(dono, emailOf(twentieth), "EDITOR");
		String c21 = invite(dono, emailOf(twentyFirst), "EDITOR");

		assertThat(accept(c20, twentieth).outcome()).isEqualTo("ACCEPTED");
		assertThat(members(dono.workspaceId())).isEqualTo(20L);
		assertThat(accept(c21, twentyFirst).outcome()).isEqualTo("FULL");

		assertThat(members(dono.workspaceId())).isEqualTo(20L);
		assertThat(roleOf(dono.workspaceId(), twentyFirst)).isNull();
		// o convite que não coube continua pendente
		assertThat(owner.client().sql("select accepted_at is null from invitations where code_hash = :h").param("h", sha256(c21))
				.query(Boolean.class).single()).isTrue();
	}

	@Test
	void twoAcceptsAtTheSameTimeOnAWorkspaceWithNineteenMembersLetExactlyOneIn() throws Exception {
		Fixture dono = fixtures().workspaceWithOwner();
		for (int i = 0; i < 18; i++) {
			fixtures().addMember(dono.workspaceId(), "VIEWER");
		}
		UUID a = fixtures().newUser();
		UUID b = fixtures().newUser();
		String ca = invite(dono, emailOf(a), "EDITOR");
		String cb = invite(dono, emailOf(b), "EDITOR");
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Outcome>> futures = new ArrayList<>();
		for (Object[] pair : new Object[][] { { ca, a }, { cb, b } }) {
			Callable<Outcome> call = () -> {
				ready.countDown();
				go.await();
				return accept((String) pair[0], (UUID) pair[1]);
			};
			futures.add(pool.submit(call));
		}
		ready.await(30, TimeUnit.SECONDS);
		go.countDown();
		List<String> outcomes = new ArrayList<>();
		for (Future<Outcome> f : futures) {
			outcomes.add(f.get(60, TimeUnit.SECONDS).outcome());
		}
		pool.shutdown();

		assertThat(outcomes).containsExactlyInAnyOrder("ACCEPTED", "FULL");
		assertThat(members(dono.workspaceId())).isEqualTo(20L);
	}

	// ---- o que a função nunca faz -----------------------------------------------------------------------------

	@Test
	void theCodeOfOneWorkspaceNeverCreatesAMembershipInAnother() throws Exception {
		Fixture a = fixtures().workspaceWithOwner();
		Fixture b = fixtures().workspaceWithOwner();
		UUID guest = fixtures().newUser();
		String code = invite(a, emailOf(guest), "VIEWER");
		long bBefore = members(b.workspaceId());
		String bState = invitationState(b.workspaceId());

		assertThat(accept(code, guest).workspaceId()).isEqualTo(a.workspaceId());

		assertThat(roleOf(b.workspaceId(), guest)).isNull();
		assertThat(members(b.workspaceId())).isEqualTo(bBefore);
		assertThat(invitationState(b.workspaceId())).isEqualTo(bState);
	}

	@Test
	void theFunctionChangesNothingBeyondTheMembershipAndTheInvitation() throws Exception {
		Fixture dono = fixtures().workspaceWithOwner();
		UUID guest = fixtures().newUser();
		String code = invite(dono, emailOf(guest), "EDITOR");
		String usersBefore = owner.client().sql("select md5(string_agg(x::text, ',' order by id)) from users x").query(String.class).single();
		long workspacesBefore = owner.client().sql("select count(*) from workspaces").query(Long.class).single();

		accept(code, guest);

		assertThat(owner.client().sql("select md5(string_agg(x::text, ',' order by id)) from users x").query(String.class).single())
				.as("users").isEqualTo(usersBefore);
		assertThat(owner.client().sql("select count(*) from workspaces").query(Long.class).single()).isEqualTo(workspacesBefore);
		assertThat(owner.client().sql("select role from workspace_members where workspace_id = :w and user_id = :u")
				.param("w", dono.workspaceId()).param("u", dono.userId()).query(String.class).single()).isEqualTo("OWNER");
	}

	// ---- quem pode executar -----------------------------------------------------------------------------------

	@Test
	void onlyTheApplicationRoleExecutesTheFunctionAndItFixesTheSearchPath() {
		boolean publicCanExecute = owner.client().sql("""
				select exists (select 1 from pg_proc p, aclexplode(p.proacl) a
				               where p.proname = 'accept_invitation' and a.grantee = 0 and a.privilege_type = 'EXECUTE')""")
				.query(Boolean.class).single();
		boolean appCanExecute = owner.client().sql("""
				select exists (select 1 from pg_proc p, aclexplode(p.proacl) a join pg_roles r on r.oid = a.grantee
				               where p.proname = 'accept_invitation' and r.rolname = 'tostao_app' and a.privilege_type = 'EXECUTE')""")
				.query(Boolean.class).single();
		boolean definer = owner.client().sql("select prosecdef from pg_proc where proname = 'accept_invitation'")
				.query(Boolean.class).single();
		String config = owner.client().sql("select array_to_string(proconfig, ',') from pg_proc where proname = 'accept_invitation'")
				.query(String.class).single();

		assertThat(publicCanExecute).as("PUBLIC não executa").isFalse();
		assertThat(appCanExecute).as("a aplicação executa").isTrue();
		assertThat(definer).as("SECURITY DEFINER").isTrue();
		assertThat(config).as("search_path fixo").contains("search_path");
	}

	@Test
	void theApplicationRoleAcceptsWithoutAnyContextAndStillCannotReadTheInvitationsTable() throws Exception {
		Fixture dono = fixtures().workspaceWithOwner();
		UUID guest = fixtures().newUser();
		String code = invite(dono, emailOf(guest), "EDITOR");

		Outcome result = new TransactionTemplate(transactionManager).execute(status -> {
			try {
				return accept(appJdbc, code, guest);
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		Long visible = new TransactionTemplate(transactionManager)
				.execute(status -> appJdbc.sql("select count(*) from invitations").query(Long.class).single());

		assertThat(result.outcome()).isEqualTo("ACCEPTED");
		// o RLS continua valendo para a mesma role fora da função: sem contexto não lê nenhum convite
		assertThat(visible).isZero();
	}

}
