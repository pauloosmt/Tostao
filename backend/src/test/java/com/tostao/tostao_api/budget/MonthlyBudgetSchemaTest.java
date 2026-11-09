package com.tostao.tostao_api.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
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
 * As invariantes da V7 valem no banco: restrições provadas com a conexão do dono (que ignora o RLS) e
 * políticas e privilégios provados como {@code tostao_app}, a role da aplicação.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MonthlyBudgetSchemaTest {

	private static final long LIMIT = 1_000_000_000_000L;
	private static final LocalDate NOV = LocalDate.of(2026, 11, 1);

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

	private void insert(World s, LocalDate monthStart, UUID category, long cents) {
		fixtures().insertMonthlyBudget(s.fx().workspaceId(), monthStart, category, cents, s.fx().userId());
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

	private static int insertAsApp(JdbcClient db, UUID workspace, UUID createdBy, LocalDate month, UUID category,
			long cents) {
		return db.sql("""
				insert into monthly_budgets (workspace_id, month_start, category_id, amount_cents, created_by)
				values (:w, :s, :c, :m, :u)""")
				.param("w", workspace).param("s", month).param("c", category).param("m", cents).param("u", createdBy)
				.update();
	}

	@Test
	void theMonthStartMustBeTheFirstDayBetween2000And2100() {
		World s = world();

		for (LocalDate bad : new LocalDate[] { LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 30),
				LocalDate.of(2100, 12, 2), LocalDate.of(2000, 1, 31), LocalDate.of(1999, 12, 1),
				LocalDate.of(2101, 1, 1) }) {
			assertThatThrownBy(() -> insert(s, bad, null, 100)).as(bad.toString())
					.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("month_start_first_day");
		}
		insert(s, LocalDate.of(2000, 1, 1), null, 100);
		insert(s, NOV, null, 100);
		insert(s, LocalDate.of(2028, 2, 1), null, 100);
		insert(s, LocalDate.of(2100, 12, 1), null, 100);
	}

	@Test
	void theAmountMustBeBetweenZeroAndTenBillionReais() {
		World s = world();

		assertThatThrownBy(() -> insert(s, NOV, s.expense(), -1)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("amount_range");
		assertThatThrownBy(() -> insert(s, NOV, s.expense(), LIMIT + 1))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("amount_range");
		insert(s, NOV, s.expense(), 0);
		insert(s, NOV.plusMonths(1), s.expense(), LIMIT);
	}

	@Test
	void theCategoryMustBeAnExpenseCategoryOfTheSameWorkspace() {
		World s = world();
		World other = world();

		assertThatThrownBy(() -> insert(s, NOV, s.income(), 100)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("monthly_budgets_category_fkey");
		assertThatThrownBy(() -> insert(s, NOV, other.expense(), 100))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("monthly_budgets_category_fkey");
		assertThatThrownBy(() -> insert(s, NOV, UUID.randomUUID(), 100))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("monthly_budgets_category_fkey");
		insert(s, NOV, s.expense(), 100);
		insert(s, NOV, null, 100);
	}

	@Test
	void thereIsOneLimitPerScopeAndMonthIncludingTheGeneralOne() {
		World s = world();
		World other = world();
		insert(s, NOV, s.expense(), 100);
		insert(s, NOV, null, 200);

		assertThatThrownBy(() -> insert(s, NOV, s.expense(), 300)).isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining("monthly_budgets_scope_key");
		assertThatThrownBy(() -> insert(s, NOV, null, 300)).isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining("monthly_budgets_scope_key");
		// outro mês e outro workspace são outros escopos
		insert(s, NOV.plusMonths(1), s.expense(), 100);
		insert(s, NOV.plusMonths(1), null, 100);
		insert(other, NOV, null, 100);
		assertThat(fixtures().countMonthlyBudgets(s.fx().workspaceId())).isEqualTo(4);
	}

	@Test
	void weeklyAndMonthlyLimitsAreIndependentTables() {
		World s = world();
		UUID w = s.fx().workspaceId();
		// o mesmo escopo, numa semana e num mês que se sobrepõem, convive sem conflito
		fixtures().insertWeeklyBudget(w, LocalDate.of(2026, 11, 2), s.expense(), 100, s.fx().userId());
		insert(s, NOV, s.expense(), 400);

		assertThat(fixtures().countWeeklyBudgets(w)).isEqualTo(1);
		assertThat(fixtures().countMonthlyBudgets(w)).isEqualTo(1);
		assertThat(owner.client().sql("select amount_cents from weekly_budgets where workspace_id = :w")
				.param("w", w).query(Long.class).single()).isEqualTo(100);
		assertThat(owner.client().sql("select amount_cents from monthly_budgets where workspace_id = :w")
				.param("w", w).query(Long.class).single()).isEqualTo(400);
	}

	@Test
	void ownersAndEditorsWriteThroughTheApplicationRole() {
		for (String role : new String[] { "OWNER", "EDITOR" }) {
			World s = world();
			Fixture who = role.equals("OWNER") ? s.fx() : fixtures().addMember(s.fx().workspaceId(), role);
			UUID w = s.fx().workspaceId();

			assertThat(this.<Integer>asApp(who.userId(), w, db -> insertAsApp(db, w, who.userId(), NOV, s.expense(), 100)))
					.as(role).isEqualTo(1);
			assertThat(this.<Integer>asApp(who.userId(), w, db -> db.sql("""
					update monthly_budgets set amount_cents = 250, updated_at = now() where workspace_id = :w""")
					.param("w", w).update())).as(role).isEqualTo(1);
			assertThat(this.<Integer>asApp(who.userId(), w, db -> db.sql("delete from monthly_budgets where workspace_id = :w")
					.param("w", w).update())).as(role).isEqualTo(1);
			assertThat(fixtures().countMonthlyBudgets(w)).as(role).isZero();
		}
	}

	@Test
	void aViewerCanReadButNotChangeAnything() {
		World s = world();
		UUID w = s.fx().workspaceId();
		Fixture viewer = fixtures().addMember(w, "VIEWER");
		insert(s, NOV, s.expense(), 100);

		// o VIEWER lê, mas não grava: o INSERT é recusado e o UPDATE e o DELETE não alcançam linha alguma
		assertThat(this.<Long>asApp(viewer.userId(), w, db -> db.sql("select count(*) from monthly_budgets where workspace_id = :w")
				.param("w", w).query(Long.class).single())).isEqualTo(1);
		assertThatThrownBy(() -> asApp(viewer.userId(), w,
				db -> insertAsApp(db, w, viewer.userId(), NOV.plusMonths(1), s.expense(), 100)))
				.rootCause().hasMessageContaining("row-level security");
		assertThat(this.<Integer>asApp(viewer.userId(), w, db -> db.sql("""
				update monthly_budgets set amount_cents = 999, updated_at = now() where workspace_id = :w""")
				.param("w", w).update())).isZero();
		assertThat(this.<Integer>asApp(viewer.userId(), w, db -> db.sql("delete from monthly_budgets where workspace_id = :w")
				.param("w", w).update())).isZero();
		assertThat(owner.client().sql("select amount_cents from monthly_budgets where workspace_id = :w")
				.param("w", w).query(Long.class).single()).isEqualTo(100);
	}

	@Test
	void theCreatorOfTheRowIsAlwaysTheSessionUser() {
		World s = world();
		UUID w = s.fx().workspaceId();
		Fixture editor = fixtures().addMember(w, "EDITOR");

		assertThatThrownBy(() -> asApp(editor.userId(), w,
				db -> insertAsApp(db, w, s.fx().userId(), NOV, s.expense(), 100)))
				.rootCause().hasMessageContaining("row-level security");
		assertThat(this.<Integer>asApp(editor.userId(), w, db -> insertAsApp(db, w, editor.userId(), NOV, s.expense(), 100)))
				.isEqualTo(1);
	}

	@Test
	void onlyTheAmountAndTheUpdateDateCanChange() {
		World s = world();
		World other = world();
		UUID w = s.fx().workspaceId();
		insert(s, NOV, s.expense(), 100);
		UUID anotherCategory = fixtures().insertCategory(w, null, "Transporte", "EXPENSE", false);
		String[] forbidden = { "workspace_id = '" + other.fx().workspaceId() + "'",
				"category_id = '" + anotherCategory + "'", "month_start = date '2026-12-01'",
				"created_by = '" + other.fx().userId() + "'", "created_at = now()", "kind = 'EXPENSE'",
				"id = gen_random_uuid()" };

		for (String assignment : forbidden) {
			assertThatThrownBy(() -> asApp(s.fx().userId(), w,
					db -> db.sql("update monthly_budgets set " + assignment + " where workspace_id = :w").param("w", w)
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
				db -> insertAsApp(db, other.fx().workspaceId(), s.fx().userId(), NOV, other.expense(), 100)))
				.rootCause().hasMessageContaining("row-level security");
		assertThatThrownBy(() -> asApp(null, null, db -> insertAsApp(db, w, s.fx().userId(), NOV, s.expense(), 100)))
				.rootCause().hasMessageContaining("row-level security");
		assertThat(fixtures().countMonthlyBudgets(w)).isZero();
		assertThat(fixtures().countMonthlyBudgets(other.fx().workspaceId())).isZero();
	}

	@Test
	void theApplicationRoleOnlySeesTheWorkspaceInItsContext() {
		World s = world();
		World other = world();
		insert(s, NOV, s.expense(), 100);
		insert(other, NOV, other.expense(), 700);

		assertThat(this.<List<Long>>asApp(s.fx().userId(), s.fx().workspaceId(),
				db -> db.sql("select amount_cents from monthly_budgets").query(Long.class).list())).containsExactly(100L);
		assertThat(this.<List<Long>>asApp(other.fx().userId(), other.fx().workspaceId(),
				db -> db.sql("select amount_cents from monthly_budgets").query(Long.class).list())).containsExactly(700L);
		assertThat(this.<List<Long>>asApp(null, null,
				db -> db.sql("select amount_cents from monthly_budgets").query(Long.class).list())).isEmpty();
		// pedir o workspace alheio no filtro não ajuda: quem barra é o RLS
		assertThat(this.<List<Long>>asApp(s.fx().userId(), s.fx().workspaceId(), db -> db
				.sql("select amount_cents from monthly_budgets where workspace_id = :w").param("w", other.fx().workspaceId())
				.query(Long.class).list())).isEmpty();
	}

}
