package com.tostao.tostao_api.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
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
 * A {@code V8} vale no banco: o estado só pode ser atualizado pela role da aplicação sob o RLS de escrita por
 * papel, e o {@code CHECK} da {@code V1} segue recusando valores fora de CLEARED e PENDING.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransactionStatusSchemaTest {

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

	private record World(Fixture fx, UUID transactionId) {
	}

	private World world(String status) {
		WorkspaceFixtures f = fixtures();
		Fixture fx = f.workspaceWithOwner();
		UUID account = f.insertAccount(fx.workspaceId(), "Conta", "CHECKING", 0, false);
		UUID category = f.insertCategory(fx.workspaceId(), null, "Mercado", "EXPENSE", false);
		UUID id = f.insertLedgerTransaction(fx.workspaceId(), account, fx.userId(), category, "EXPENSE", 100,
				LocalDate.of(2026, 11, 5), "Compra", false, status);
		return new World(fx, id);
	}

	/** Roda como {@code tostao_app}, numa transação própria, com o contexto dado (nulo = sem contexto). */
	private <T> T asApp(UUID user, UUID workspace, Function<JdbcClient, T> work) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			if (user != null && workspace != null) {
				tenant.enterWorkspace(user, workspace);
			}
			return work.apply(appJdbc);
		});
	}

	private int setStatus(UUID user, UUID workspace, String status, UUID id) {
		return asApp(user, workspace, db -> db.sql("update transactions set status = :s, updated_at = now() where id = :id")
				.param("s", status).param("id", id).update());
	}

	@Test
	void ownersAndEditorsCanChangeTheStatusBothWays() {
		for (String role : new String[] { "OWNER", "EDITOR" }) {
			World w = world("PENDING");
			Fixture who = role.equals("OWNER") ? w.fx() : fixtures().addMember(w.fx().workspaceId(), role);

			assertThat(setStatus(who.userId(), w.fx().workspaceId(), "CLEARED", w.transactionId())).as(role).isEqualTo(1);
			assertThat(PendingScenario.statusOf(owner, w.transactionId())).as(role).isEqualTo("CLEARED");
			assertThat(setStatus(who.userId(), w.fx().workspaceId(), "PENDING", w.transactionId())).as(role).isEqualTo(1);
			assertThat(PendingScenario.statusOf(owner, w.transactionId())).as(role).isEqualTo("PENDING");
		}
	}

	@Test
	void aViewerCannotChangeTheStatus() {
		World w = world("PENDING");
		Fixture viewer = fixtures().addMember(w.fx().workspaceId(), "VIEWER");

		assertThat(setStatus(viewer.userId(), w.fx().workspaceId(), "CLEARED", w.transactionId())).isZero();
		assertThat(PendingScenario.statusOf(owner, w.transactionId())).isEqualTo("PENDING");
	}

	@Test
	void anotherWorkspaceAndNoContextReachNothing() {
		World a = world("PENDING");
		World b = world("PENDING");

		// o contexto do workspace A não alcança o lançamento do B, mesmo sem filtro de workspace
		assertThat(setStatus(a.fx().userId(), a.fx().workspaceId(), "CLEARED", b.transactionId())).isZero();
		// sem contexto, nada é alcançado
		assertThat(setStatus(null, null, "CLEARED", a.transactionId())).isZero();
		assertThat(PendingScenario.statusOf(owner, a.transactionId())).isEqualTo("PENDING");
		assertThat(PendingScenario.statusOf(owner, b.transactionId())).isEqualTo("PENDING");
	}

	@Test
	void theDatabaseStillRefusesAnUnknownStatus() {
		World w = world("CLEARED");

		assertThatThrownBy(() -> owner.client().sql("update transactions set status = 'OUTRO' where id = :id")
				.param("id", w.transactionId()).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> world("OUTRO")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theOtherColumnsStayImmutableForTheApplicationRole() {
		World w = world("PENDING");
		UUID ws = w.fx().workspaceId();

		for (String change : new String[] { "created_by = '" + UUID.randomUUID() + "'", "type = 'INCOME'",
				"workspace_id = '" + UUID.randomUUID() + "'", "transfer_group_id = '" + UUID.randomUUID() + "'",
				"created_at = now()", "id = '" + UUID.randomUUID() + "'" }) {
			assertThatThrownBy(() -> asApp(w.fx().userId(), ws, db -> db
					.sql("update transactions set " + change + " where id = '" + w.transactionId() + "'").update()))
					.as(change).rootCause().hasMessageContaining("permission denied");
		}
	}

	@Test
	void aRowCreatedWithoutAStatusIsCleared() {
		WorkspaceFixtures f = fixtures();
		Fixture fx = f.workspaceWithOwner();
		UUID account = f.insertAccount(fx.workspaceId(), "Conta", "CHECKING", 0, false);

		UUID id = owner.client().sql("""
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description, created_by)
				values (:w, :a, 'INCOME', 100, date '2026-11-05', 'Sem estado', :u) returning id""")
				.param("w", fx.workspaceId()).param("a", account).param("u", fx.userId()).query(UUID.class).single();

		assertThat(PendingScenario.statusOf(owner, id)).isEqualTo("CLEARED");
	}

}
