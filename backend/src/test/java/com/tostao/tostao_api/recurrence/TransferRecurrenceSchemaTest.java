package com.tostao.tostao_api.recurrence;

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
 * A {@code V15} vale no banco: a forma da recorrência (transferência tem destino e não tem categoria; receita e despesa o
 * contrário), o destino do mesmo workspace, o índice que aceita uma saída e uma entrada por número (e nada além) e a
 * restrição das ocorrências, sem enfraquecer o que a V10 já garantia para receita e despesa.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransferRecurrenceSchemaTest {

	private static final LocalDate START = LocalDate.of(2026, 11, 5);

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

	private record World(Fixture fx, UUID checking, UUID savings, UUID expense, UUID income) {
	}

	private World world() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		Fixture fx = f.workspaceWithOwner();
		return new World(fx, f.insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false),
				f.insertAccount(fx.workspaceId(), "Poupança", "SAVINGS", 0, false),
				f.insertCategory(fx.workspaceId(), null, "Aluguel", "EXPENSE", false),
				f.insertCategory(fx.workspaceId(), null, "Salário", "INCOME", false));
	}

	/** Insere uma recorrência pela conexão do dono (sem RLS), com a forma dada. */
	private UUID recurrence(World w, String type, UUID account, UUID destination, UUID category) {
		return owner.client().sql("""
				insert into recurrences (workspace_id, account_id, destination_account_id, category_id, type, amount_cents,
				                         description, frequency, start_on, created_by)
				values (:w, :a, :d, :c, :t, 50000, 'Reserva', 'MONTHLY', :s, :u) returning id""")
				.param("w", w.fx().workspaceId()).param("a", account).param("d", destination).param("c", category)
				.param("t", type).param("s", START).param("u", w.fx().userId()).query(UUID.class).single();
	}

	private UUID transfer(World w) {
		return recurrence(w, "TRANSFER", w.checking(), w.savings(), null);
	}

	private void refuse(World w, String type, UUID account, UUID destination, UUID category) {
		assertThatThrownBy(() -> recurrence(w, type, account, destination, category)).isInstanceOf(DataIntegrityViolationException.class);
	}

	/** Uma linha de ocorrência pela conexão do dono: receita/despesa (categoria) ou uma ponta de transferência (grupo). */
	private UUID occurrenceRow(World w, UUID recurrence, int number, String type, UUID account, long cents, UUID category, UUID group,
			boolean deleted) {
		return owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on, description,
				                          created_by, status, transfer_group_id, recurrence_id, occurrence_number, deleted_at)
				values (:w, :a, :c, :t, :m, :d, 'Reserva', :u, 'PENDING', :g, :r, :n, case when :x then now() else null end)
				returning id""")
				.param("w", w.fx().workspaceId()).param("a", account).param("c", category).param("t", type).param("m", cents)
				.param("d", START).param("u", w.fx().userId()).param("g", group).param("r", recurrence).param("n", number)
				.param("x", deleted).query(UUID.class).single();
	}

	private void leg(World w, UUID recurrence, int number, boolean out, UUID group, boolean deleted) {
		occurrenceRow(w, recurrence, number, "TRANSFER", out ? w.checking() : w.savings(), out ? -50000 : 50000, null, group, deleted);
	}

	private <T> T asApp(World w, Function<JdbcClient, T> work) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(w.fx().userId(), w.fx().workspaceId());
			return work.apply(appJdbc);
		});
	}

	// ---- a forma da recorrência -------------------------------------------------------------------------------

	@Test
	void aTransferHasADestinationAndNoCategory() {
		World w = world();

		assertThat(transfer(w)).isNotNull();
		assertThat(owner.client().sql("select category_id is null and destination_account_id is not null from recurrences where id = :id")
				.param("id", transfer(w)).query(Boolean.class).single()).isTrue();
	}

	@Test
	void aTransferWithoutADestinationWithTheSameAccountOrWithACategoryIsRefused() {
		World w = world();

		refuse(w, "TRANSFER", w.checking(), null, null);
		refuse(w, "TRANSFER", w.checking(), w.checking(), null);
		refuse(w, "TRANSFER", w.checking(), w.savings(), w.expense());
		refuse(w, "TRANSFER", w.checking(), w.savings(), w.income());
	}

	@Test
	void anIncomeOrExpenseKeepsItsCategoryAndRefusesADestination() {
		World w = world();

		assertThat(recurrence(w, "EXPENSE", w.checking(), null, w.expense())).isNotNull();
		assertThat(recurrence(w, "INCOME", w.checking(), null, w.income())).isNotNull();
		refuse(w, "EXPENSE", w.checking(), null, null);
		refuse(w, "INCOME", w.checking(), null, null);
		refuse(w, "EXPENSE", w.checking(), w.savings(), w.expense());
		refuse(w, "INCOME", w.checking(), w.savings(), w.income());
		// a categoria continua tendo o tipo da recorrência
		refuse(w, "EXPENSE", w.checking(), null, w.income());
		refuse(w, "INCOME", w.checking(), null, w.expense());
	}

	@Test
	void anUnknownTypeIsStillRefused() {
		World w = world();

		refuse(w, "OUTRO", w.checking(), w.savings(), null);
		refuse(w, "", w.checking(), w.savings(), null);
	}

	@Test
	void theDestinationMustBelongToTheSameWorkspace() {
		World w = world();
		World other = world();

		refuse(w, "TRANSFER", w.checking(), other.savings(), null);
		refuse(w, "TRANSFER", other.checking(), w.savings(), null);
	}

	@Test
	void theApplicationInsertsATransferRecurrenceButNeverChangesItsDestination() {
		World w = world();
		UUID id = asApp(w, db -> db.sql("""
				insert into recurrences (workspace_id, account_id, destination_account_id, type, amount_cents, description,
				                         frequency, start_on, created_by)
				values (:w, :a, :d, 'TRANSFER', 100, 'Reserva', 'MONTHLY', :s, :u) returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.checking()).param("d", w.savings()).param("s", START)
				.param("u", w.fx().userId()).query(UUID.class).single());

		assertThat(id).isNotNull();
		assertThatThrownBy(() -> asApp(w, db -> db.sql("update recurrences set destination_account_id = :d where id = :id")
				.param("d", w.checking()).param("id", id).update())).rootCause().hasMessageContaining("permission denied");
		// o encerramento continua possível
		int ended = asApp(w, db -> db.sql("update recurrences set ended_at = now(), updated_at = now() where id = :id").param("id", id).update());
		assertThat(ended).isEqualTo(1);
	}

	@Test
	void aViewerCannotInsertATransferRecurrence() {
		World w = world();
		Fixture viewer = new WorkspaceFixtures(owner, tokens).addMember(w.fx().workspaceId(), "VIEWER");

		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(viewer.userId(), w.fx().workspaceId());
			return appJdbc.sql("""
					insert into recurrences (workspace_id, account_id, destination_account_id, type, amount_cents, description,
					                         frequency, start_on, created_by)
					values (:w, :a, :d, 'TRANSFER', 100, 'Reserva', 'MONTHLY', :s, :u)""")
					.param("w", w.fx().workspaceId()).param("a", w.checking()).param("d", w.savings()).param("s", START)
					.param("u", viewer.userId()).update();
		})).rootCause().hasMessageContaining("row-level security");
	}

	// ---- as ocorrências ---------------------------------------------------------------------------------------

	@Test
	void aTransferOccurrenceHasOneOutflowAndOneInflowPerNumber() {
		World w = world();
		UUID recurrence = transfer(w);
		UUID group = UUID.randomUUID();

		leg(w, recurrence, 0, true, group, false);
		leg(w, recurrence, 0, false, group, false);
		// um número a mais é outra ocorrência
		UUID second = UUID.randomUUID();
		leg(w, recurrence, 1, true, second, false);
		leg(w, recurrence, 1, false, second, false);

		assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = :r").param("r", recurrence)
				.query(Long.class).single()).isEqualTo(4L);
	}

	@Test
	void aSecondOutflowOrASecondInflowOfTheSameNumberIsRefusedEvenIfTheFirstWasDeleted() {
		World w = world();
		UUID recurrence = transfer(w);
		leg(w, recurrence, 0, true, UUID.randomUUID(), false);
		leg(w, recurrence, 0, false, UUID.randomUUID(), true);

		assertThatThrownBy(() -> leg(w, recurrence, 0, true, UUID.randomUUID(), false)).isInstanceOf(DataIntegrityViolationException.class);
		// a entrada excluída continua ocupando o número
		assertThatThrownBy(() -> leg(w, recurrence, 0, false, UUID.randomUUID(), false)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void anIncomeOrExpenseOccurrenceStillHasOneRowPerNumber() {
		World w = world();
		UUID expense = recurrence(w, "EXPENSE", w.checking(), null, w.expense());
		UUID income = recurrence(w, "INCOME", w.checking(), null, w.income());

		occurrenceRow(w, expense, 0, "EXPENSE", w.checking(), -100, w.expense(), null, false);
		occurrenceRow(w, income, 0, "INCOME", w.checking(), 100, w.income(), null, false);

		assertThatThrownBy(() -> occurrenceRow(w, expense, 0, "EXPENSE", w.checking(), -100, w.expense(), null, false))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> occurrenceRow(w, expense, 0, "EXPENSE", w.checking(), -100, w.expense(), null, true))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> occurrenceRow(w, income, 0, "INCOME", w.checking(), 100, w.income(), null, false))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theOccurrenceNumberAndTheRecurrenceTravelTogetherAndNeverWithAnInstallment() {
		World w = world();
		UUID recurrence = transfer(w);

		// número sem recorrência e recorrência sem número
		assertThatThrownBy(() -> owner.client().sql("""
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description, created_by,
				                          transfer_group_id, occurrence_number)
				values (:w, :a, 'TRANSFER', -1, :d, 'x', :u, gen_random_uuid(), 3)""")
				.param("w", w.fx().workspaceId()).param("a", w.checking()).param("d", START).param("u", w.fx().userId()).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> owner.client().sql("""
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description, created_by,
				                          transfer_group_id, recurrence_id)
				values (:w, :a, 'TRANSFER', -1, :d, 'x', :u, gen_random_uuid(), :r)""")
				.param("w", w.fx().workspaceId()).param("a", w.checking()).param("d", START).param("u", w.fx().userId())
				.param("r", recurrence).update()).isInstanceOf(DataIntegrityViolationException.class);
		// número negativo
		assertThatThrownBy(() -> leg(w, recurrence, -1, true, UUID.randomUUID(), false)).isInstanceOf(DataIntegrityViolationException.class);
		// recorrência de outro workspace
		World other = world();
		UUID foreign = transfer(other);
		assertThatThrownBy(() -> leg(w, foreign, 0, true, UUID.randomUUID(), false)).isInstanceOf(DataIntegrityViolationException.class);
	}

}
