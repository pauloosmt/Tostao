package com.tostao.tostao_api.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * A {@code V9} vale no banco: as colunas de parcela andam juntas, só despesa é parcela, o número fica entre 1 e o
 * total, o número é único no grupo, e a role da aplicação grava as colunas na criação mas nunca as altera.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InstallmentSchemaTest {

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

	private record World(Fixture fx, UUID account, UUID expenseCategory, UUID incomeCategory) {
	}

	private World world() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		Fixture fx = f.workspaceWithOwner();
		UUID account = f.insertAccount(fx.workspaceId(), "Conta", "CHECKING", 0, false);
		UUID expense = f.insertCategory(fx.workspaceId(), null, "Eletrônicos", "EXPENSE", false);
		UUID income = f.insertCategory(fx.workspaceId(), null, "Salário", "INCOME", false);
		return new World(fx, account, expense, income);
	}

	/**
	 * Insere uma linha pela conexão do dono (sem RLS) com as colunas de parcela dadas. {@code group}, {@code number}
	 * e {@code count} nulos deixam a coluna nula.
	 */
	private UUID insert(World w, String type, UUID category, UUID group, Integer number, Integer count) {
		long cents = type.equals("INCOME") ? 1000 : -1000;
		return owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, installment_group_id, installment_number,
				                          installment_count)
				values (:w, :a, :c, :t, :amount, date '2026-11-10', 'Notebook', :u, :g, :n, :k)
				returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", category).param("t", type)
				.param("amount", cents).param("u", w.fx().userId()).param("g", group).param("n", number)
				.param("k", count).query(UUID.class).single();
	}

	private UUID parcel(World w, UUID group, int number, int count) {
		return insert(w, "EXPENSE", w.expenseCategory(), group, number, count);
	}

	/** Roda como {@code tostao_app}, numa transação própria, com o contexto dado. */
	private <T> T asApp(UUID user, UUID workspace, Function<JdbcClient, T> work) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(user, workspace);
			return work.apply(appJdbc);
		});
	}

	private int insertAsApp(UUID user, World w, UUID group, int number, int count) {
		return asApp(user, w.fx().workspaceId(), db -> db.sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, installment_group_id, installment_number,
				                          installment_count)
				values (:w, :a, :c, 'EXPENSE', -1000, date '2026-11-10', 'Notebook', :u, :g, :n, :k)""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", w.expenseCategory())
				.param("u", user).param("g", group).param("n", number).param("k", count).update());
	}

	@Test
	void ordinaryRowsKeepTheThreeColumnsNull() {
		World w = world();
		UUID id = insert(w, "EXPENSE", w.expenseCategory(), null, null, null);

		assertThat(owner.client().sql("""
				select installment_group_id is null and installment_number is null and installment_count is null
				from transactions where id = :id""").param("id", id).query(Boolean.class).single()).isTrue();
	}

	@Test
	void theThreeColumnsComeTogetherOrNotAtAll() {
		World w = world();
		UUID group = UUID.randomUUID();

		assertThatThrownBy(() -> insert(w, "EXPENSE", w.expenseCategory(), group, null, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(w, "EXPENSE", w.expenseCategory(), group, 1, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(w, "EXPENSE", w.expenseCategory(), group, null, 3))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(w, "EXPENSE", w.expenseCategory(), null, 1, 3))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(w, "EXPENSE", w.expenseCategory(), null, null, 3))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theCountGoesFromTwoToFortyEight() {
		World w = world();

		for (int count : new int[] { -1, 0, 1, 49, 100 }) {
			assertThatThrownBy(() -> parcel(w, UUID.randomUUID(), 1, count)).as("count " + count)
					.isInstanceOf(DataIntegrityViolationException.class);
		}
		assertThat(parcel(w, UUID.randomUUID(), 1, 2)).isNotNull();
		assertThat(parcel(w, UUID.randomUUID(), 48, 48)).isNotNull();
	}

	@Test
	void theNumberStaysBetweenOneAndTheCount() {
		World w = world();

		assertThatThrownBy(() -> parcel(w, UUID.randomUUID(), 0, 3)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> parcel(w, UUID.randomUUID(), -1, 3)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> parcel(w, UUID.randomUUID(), 4, 3)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(parcel(w, UUID.randomUUID(), 1, 3)).isNotNull();
		assertThat(parcel(w, UUID.randomUUID(), 3, 3)).isNotNull();
	}

	@Test
	void onlyAnExpenseCanBeAnInstallment() {
		World w = world();

		assertThatThrownBy(() -> insert(w, "INCOME", w.incomeCategory(), UUID.randomUUID(), 1, 3))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> owner.client().sql("""
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description,
				                          created_by, transfer_group_id, installment_group_id, installment_number,
				                          installment_count)
				values (:w, :a, 'TRANSFER', -1000, date '2026-11-10', 'Transf', :u, gen_random_uuid(),
				        gen_random_uuid(), 1, 3)""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("u", w.fx().userId()).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theNumberIsUniqueInTheGroupAndOnlyThere() {
		World w = world();
		UUID group = UUID.randomUUID();
		parcel(w, group, 1, 3);

		assertThatThrownBy(() -> parcel(w, group, 1, 3)).isInstanceOf(DataIntegrityViolationException.class);
		// outro número do mesmo grupo e o mesmo número em outro grupo são aceitos
		assertThat(parcel(w, group, 2, 3)).isNotNull();
		assertThat(parcel(w, UUID.randomUUID(), 1, 3)).isNotNull();
		// linhas comuns (as três colunas nulas) repetem à vontade: o índice é parcial
		insert(w, "EXPENSE", w.expenseCategory(), null, null, null);
		insert(w, "EXPENSE", w.expenseCategory(), null, null, null);
	}

	@Test
	void anExcludedInstallmentKeepsItsNumber() {
		World w = world();
		UUID group = UUID.randomUUID();
		UUID id = parcel(w, group, 1, 3);
		owner.client().sql("update transactions set deleted_at = now() where id = :id").param("id", id).update();

		// o número continua ocupado: a linha excluída segue existindo
		assertThatThrownBy(() -> parcel(w, group, 1, 3)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void ownersAndEditorsInsertInstallmentsAsTheApplicationRole() {
		World w = world();
		Fixture editor = new WorkspaceFixtures(owner, tokens).addMember(w.fx().workspaceId(), "EDITOR");

		assertThat(insertAsApp(w.fx().userId(), w, UUID.randomUUID(), 1, 3)).isEqualTo(1);
		assertThat(insertAsApp(editor.userId(), w, UUID.randomUUID(), 1, 3)).isEqualTo(1);
	}

	@Test
	void aViewerCannotInsertInstallments() {
		World w = world();
		Fixture viewer = new WorkspaceFixtures(owner, tokens).addMember(w.fx().workspaceId(), "VIEWER");

		assertThatThrownBy(() -> insertAsApp(viewer.userId(), w, UUID.randomUUID(), 1, 3)).rootCause()
				.hasMessageContaining("row-level security");
	}

	@Test
	void theApplicationRoleNeverChangesTheInstallmentColumns() {
		World w = world();
		UUID id = parcel(w, UUID.randomUUID(), 1, 3);

		for (String change : new String[] { "installment_group_id = gen_random_uuid()", "installment_group_id = null",
				"installment_number = 2", "installment_number = null", "installment_count = 5",
				"installment_count = null" }) {
			assertThatThrownBy(() -> asApp(w.fx().userId(), w.fx().workspaceId(), db -> db
					.sql("update transactions set " + change + " where id = '" + id + "'").update())).as(change)
					.rootCause().hasMessageContaining("permission denied");
		}
	}

	@Test
	void theApplicationRoleStillChangesTheStatusAndExcludesAnInstallment() {
		World w = world();
		UUID id = parcel(w, UUID.randomUUID(), 2, 3);
		UUID ws = w.fx().workspaceId();

		int statusChanged = asApp(w.fx().userId(), ws,
				db -> db.sql("update transactions set status = 'PENDING' where id = :id").param("id", id).update());
		int excluded = asApp(w.fx().userId(), ws,
				db -> db.sql("update transactions set deleted_at = now(), updated_at = now() where id = :id")
						.param("id", id).update());

		assertThat(statusChanged).isEqualTo(1);
		assertThat(excluded).isEqualTo(1);
	}

}
