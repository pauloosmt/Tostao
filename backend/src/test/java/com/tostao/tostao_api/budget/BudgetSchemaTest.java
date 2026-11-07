package com.tostao.tostao_api.budget;

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
import org.springframework.dao.DuplicateKeyException;
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
 * As invariantes da V6 valem no banco: restrições provadas com a conexão do dono (que ignora o RLS) e
 * políticas e privilégios provados como {@code tostao_app}, a role da aplicação.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BudgetSchemaTest {

	private static final long LIMIT = 1_000_000_000_000L;
	private static final LocalDate MONDAY = LocalDate.of(2026, 11, 2);

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

	private record World(Fixture fx, UUID expense, UUID income) {
	}

	private World world() {
		Fixture fx = fixtures().workspaceWithOwner();
		return new World(fx, fixtures().insertCategory(fx.workspaceId(), null, "Mercado", "EXPENSE", false),
				fixtures().insertCategory(fx.workspaceId(), null, "Salario", "INCOME", false));
	}

	private void insert(World s, LocalDate weekStart, UUID category, long cents) {
		fixtures().insertWeeklyBudget(s.fx().workspaceId(), weekStart, category, cents, s.fx().userId());
	}

	/** Roda como {@code tostao_app}, numa transação própria (confirmada no fim), com o contexto dado. */
	private <T> T asApp(UUID user, UUID workspace, Function<JdbcClient, T> work) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			if (user != null && workspace != null) {
				tenant.enterWorkspace(user, workspace);
			}
			return work.apply(appJdbc);
		});
	}

	private static int insertAsApp(JdbcClient db, UUID workspace, UUID createdBy, LocalDate week, UUID category,
			long cents) {
		return db.sql("""
				insert into weekly_budgets (workspace_id, week_start, category_id, amount_cents, created_by)
				values (:w, :s, :c, :m, :u)""")
				.param("w", workspace).param("s", week).param("c", category).param("m", cents).param("u", createdBy)
				.update();
	}

	@Test
	void theWeekStartMustBeAMondayBetween2000And2100() {
		World s = world();

		for (LocalDate bad : new LocalDate[] { LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 8),
				LocalDate.of(2000, 1, 2), LocalDate.of(1999, 12, 27), LocalDate.of(2100, 12, 28),
				LocalDate.of(2101, 1, 4) }) {
			assertThatThrownBy(() -> insert(s, bad, null, 100)).as(bad.toString())
					.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("week_start_monday");
		}
		insert(s, LocalDate.of(2000, 1, 3), null, 100);
		insert(s, MONDAY, null, 100);
		insert(s, LocalDate.of(2100, 12, 27), null, 100);
	}

	@Test
	void theAmountMustBeBetweenZeroAndTenBillionReais() {
		World s = world();

		assertThatThrownBy(() -> insert(s, MONDAY, s.expense(), -1)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("amount_range");
		assertThatThrownBy(() -> insert(s, MONDAY, s.expense(), LIMIT + 1))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("amount_range");
		insert(s, MONDAY, s.expense(), 0);
		insert(s, MONDAY.plusDays(7), s.expense(), LIMIT);
	}

	@Test
	void theCategoryMustBeAnExpenseCategoryOfTheSameWorkspace() {
		World s = world();
		World other = world();

		assertThatThrownBy(() -> insert(s, MONDAY, s.income(), 100)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("weekly_budgets_category_fkey");
		assertThatThrownBy(() -> insert(s, MONDAY, other.expense(), 100))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("weekly_budgets_category_fkey");
		assertThatThrownBy(() -> insert(s, MONDAY, UUID.randomUUID(), 100))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("weekly_budgets_category_fkey");
		insert(s, MONDAY, s.expense(), 100);
		insert(s, MONDAY, null, 100);
	}

	@Test
	void thereIsOneLimitPerScopeAndWeekIncludingTheGeneralOne() {
		World s = world();
		World other = world();
		insert(s, MONDAY, s.expense(), 100);
		insert(s, MONDAY, null, 200);

		assertThatThrownBy(() -> insert(s, MONDAY, s.expense(), 300)).isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining("weekly_budgets_scope_key");
		assertThatThrownBy(() -> insert(s, MONDAY, null, 300)).isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining("weekly_budgets_scope_key");
		// outra semana e outro workspace são outros escopos
		insert(s, MONDAY.plusDays(7), s.expense(), 100);
		insert(s, MONDAY.plusDays(7), null, 100);
		insert(other, MONDAY, null, 100);
		assertThat(fixtures().countWeeklyBudgets(s.fx().workspaceId())).isEqualTo(4);
	}

	@Test
	void ownersAndEditorsWriteThroughTheApplicationRole() {
		for (String role : new String[] { "OWNER", "EDITOR" }) {
			World s = world();
			Fixture who = role.equals("OWNER") ? s.fx() : fixtures().addMember(s.fx().workspaceId(), role);
			UUID w = s.fx().workspaceId();

			assertThat(this.<Integer>asApp(who.userId(), w, db -> insertAsApp(db, w, who.userId(), MONDAY, s.expense(), 100)))
					.as(role).isEqualTo(1);
			assertThat(this.<Integer>asApp(who.userId(), w, db -> db.sql("""
					update weekly_budgets set amount_cents = 250, updated_at = now() where workspace_id = :w""")
					.param("w", w).update())).as(role).isEqualTo(1);
			assertThat(this.<Integer>asApp(who.userId(), w, db -> db.sql("delete from weekly_budgets where workspace_id = :w")
					.param("w", w).update())).as(role).isEqualTo(1);
			assertThat(fixtures().countWeeklyBudgets(w)).as(role).isZero();
		}
	}

	@Test
	void anEditorCanDeleteAndAViewerCannotChangeAnything() {
		World s = world();
		UUID w = s.fx().workspaceId();
		Fixture editor = fixtures().addMember(w, "EDITOR");
		Fixture viewer = fixtures().addMember(w, "VIEWER");
		insert(s, MONDAY, s.expense(), 100);

		// o VIEWER lê, mas não grava: o INSERT é recusado e o UPDATE e o DELETE não alcançam linha alguma
		assertThat(this.<Long>asApp(viewer.userId(), w, db -> db.sql("select count(*) from weekly_budgets where workspace_id = :w")
				.param("w", w).query(Long.class).single())).isEqualTo(1);
		assertThatThrownBy(() -> asApp(viewer.userId(), w,
				db -> insertAsApp(db, w, viewer.userId(), MONDAY.plusDays(7), s.expense(), 100)))
				.rootCause().hasMessageContaining("row-level security");
		assertThat(this.<Integer>asApp(viewer.userId(), w, db -> db.sql("""
				update weekly_budgets set amount_cents = 999, updated_at = now() where workspace_id = :w""")
				.param("w", w).update())).isZero();
		assertThat(this.<Integer>asApp(viewer.userId(), w, db -> db.sql("delete from weekly_budgets where workspace_id = :w")
				.param("w", w).update())).isZero();
		assertThat(owner.client().sql("select amount_cents from weekly_budgets where workspace_id = :w")
				.param("w", w).query(Long.class).single()).isEqualTo(100);

		// o EDITOR apaga de verdade
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			tenant.enterWorkspace(editor.userId(), w);
			assertThat(appJdbc.sql("delete from weekly_budgets where workspace_id = :w").param("w", w).update())
					.isEqualTo(1);
		});
		assertThat(fixtures().countWeeklyBudgets(w)).isZero();
	}

	@Test
	void theCreatorOfTheRowIsAlwaysTheSessionUser() {
		World s = world();
		UUID w = s.fx().workspaceId();
		Fixture editor = fixtures().addMember(w, "EDITOR");

		assertThatThrownBy(() -> asApp(editor.userId(), w,
				db -> insertAsApp(db, w, s.fx().userId(), MONDAY, s.expense(), 100)))
				.rootCause().hasMessageContaining("row-level security");
		assertThat(this.<Integer>asApp(editor.userId(), w, db -> insertAsApp(db, w, editor.userId(), MONDAY, s.expense(), 100)))
				.isEqualTo(1);
	}

	@Test
	void onlyTheAmountAndTheUpdateDateCanChange() {
		World s = world();
		World other = world();
		UUID w = s.fx().workspaceId();
		insert(s, MONDAY, s.expense(), 100);
		UUID anotherCategory = fixtures().insertCategory(w, null, "Transporte", "EXPENSE", false);
		String[] forbidden = { "workspace_id = '" + other.fx().workspaceId() + "'",
				"category_id = '" + anotherCategory + "'", "week_start = date '2026-11-09'",
				"created_by = '" + other.fx().userId() + "'", "created_at = now()", "kind = 'EXPENSE'",
				"id = gen_random_uuid()" };

		for (String assignment : forbidden) {
			assertThatThrownBy(() -> asApp(s.fx().userId(), w,
					db -> db.sql("update weekly_budgets set " + assignment + " where workspace_id = :w").param("w", w)
							.update()))
					.as(assignment).rootCause().hasMessageContaining("permission denied");
		}
	}

	@Test
	void theApplicationRoleCannotInsertIntoAnotherWorkspaceOrWithoutContext() {
		World s = world();
		World other = world();
		UUID w = s.fx().workspaceId();

		assertThatThrownBy(() -> asApp(s.fx().userId(), w,
				db -> insertAsApp(db, other.fx().workspaceId(), s.fx().userId(), MONDAY, other.expense(), 100)))
				.rootCause().hasMessageContaining("row-level security");
		assertThatThrownBy(() -> asApp(null, null, db -> insertAsApp(db, w, s.fx().userId(), MONDAY, s.expense(), 100)))
				.rootCause().hasMessageContaining("row-level security");
		assertThat(fixtures().countWeeklyBudgets(w)).isZero();
		assertThat(fixtures().countWeeklyBudgets(other.fx().workspaceId())).isZero();
	}

	@Test
	void theApplicationRoleOnlySeesTheWorkspaceInItsContext() {
		World s = world();
		World other = world();
		insert(s, MONDAY, s.expense(), 100);
		insert(other, MONDAY, other.expense(), 700);

		assertThat(this.<java.util.List<Long>>asApp(s.fx().userId(), s.fx().workspaceId(), db -> db.sql("select amount_cents from weekly_budgets")
				.query(Long.class).list())).containsExactly(100L);
		assertThat(this.<java.util.List<Long>>asApp(other.fx().userId(), other.fx().workspaceId(),
				db -> db.sql("select amount_cents from weekly_budgets").query(Long.class).list())).containsExactly(700L);
		assertThat(this.<java.util.List<Long>>asApp(null, null, db -> db.sql("select amount_cents from weekly_budgets").query(Long.class).list()))
				.isEmpty();
		// pedir o workspace alheio no filtro não ajuda: quem barra é o RLS
		assertThat(this.<java.util.List<Long>>asApp(s.fx().userId(), s.fx().workspaceId(), db -> db
				.sql("select amount_cents from weekly_budgets where workspace_id = :w").param("w", other.fx().workspaceId())
				.query(Long.class).list())).isEmpty();
	}

}
