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
 * A {@code V10} vale no banco: a recorrência é um registro com restrições próprias e RLS por papel, a ocorrência
 * pertence ao mesmo workspace da recorrência, o número da ocorrência é único (mesmo excluída), e o marcador de
 * efetivação automática só existe em lançamento pendente.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RecurrenceSchemaTest {

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

	private static final LocalDate START = LocalDate.of(2026, 11, 10);

	private record World(Fixture fx, UUID account, UUID expense, UUID income) {
	}

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private World world() {
		WorkspaceFixtures f = fixtures();
		Fixture fx = f.workspaceWithOwner();
		UUID account = f.insertAccount(fx.workspaceId(), "Conta", "CHECKING", 0, false);
		UUID expense = f.insertCategory(fx.workspaceId(), null, "Aluguel", "EXPENSE", false);
		UUID income = f.insertCategory(fx.workspaceId(), null, "Salário", "INCOME", false);
		return new World(fx, account, expense, income);
	}

	/** Insere uma recorrência pela conexão do dono (sem RLS). */
	private UUID recurrence(UUID workspace, UUID user, UUID account, UUID category, String type, long amount,
			String description, String frequency, LocalDate start, LocalDate end) {
		return owner.client().sql("""
				insert into recurrences (workspace_id, account_id, category_id, type, amount_cents, description,
				                         frequency, start_on, end_on, created_by)
				values (:w, :a, :c, :t, :m, :d, :f, :s, :e, :u) returning id""")
				.param("w", workspace).param("a", account).param("c", category).param("t", type).param("m", amount)
				.param("d", description).param("f", frequency).param("s", start).param("e", end).param("u", user)
				.query(UUID.class).single();
	}

	private UUID recurrence(World w) {
		return recurrence(w.fx().workspaceId(), w.fx().userId(), w.account(), w.expense(), "EXPENSE", 150_000, "Aluguel",
				"MONTHLY", START, null);
	}

	/** Insere uma linha de transactions pela conexão do dono, com as colunas de ocorrência e marcador dadas. */
	private UUID line(World w, String type, UUID category, String status, boolean autoClear, UUID recurrence,
			Integer number) {
		long cents = type.equals("INCOME") ? 1_000 : -1_000;
		return owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, status, auto_clear, recurrence_id, occurrence_number)
				values (:w, :a, :c, :t, :m, :d, 'Aluguel', :u, :st, :ac, :r, :n) returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", category).param("t", type)
				.param("m", cents).param("d", START).param("u", w.fx().userId()).param("st", status)
				.param("ac", autoClear).param("r", recurrence).param("n", number).query(UUID.class).single();
	}

	private UUID occurrence(World w, UUID recurrence, int number) {
		return line(w, "EXPENSE", w.expense(), "PENDING", false, recurrence, number);
	}

	/** Roda como {@code tostao_app}, numa transação própria, com o contexto dado. */
	private <T> T asApp(UUID user, UUID workspace, Function<JdbcClient, T> work) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(user, workspace);
			return work.apply(appJdbc);
		});
	}

	private int asAppUpdate(UUID user, UUID workspace, String sql, UUID id) {
		return asApp(user, workspace, db -> db.sql(sql).param("id", id).update());
	}

	// ---- o marcador de efetivação automática ------------------------------------------------------------------

	@Test
	void theMarkerOnlyExistsOnAPendingLine() {
		World w = world();

		assertThat(line(w, "EXPENSE", w.expense(), "PENDING", true, null, null)).isNotNull();
		assertThatThrownBy(() -> line(w, "EXPENSE", w.expense(), "CLEARED", true, null, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		UUID cleared = line(w, "EXPENSE", w.expense(), "CLEARED", false, null, null);
		assertThatThrownBy(() -> owner.client().sql("update transactions set auto_clear = true where id = :id")
				.param("id", cleared).update()).isInstanceOf(DataIntegrityViolationException.class);
		UUID marked = line(w, "EXPENSE", w.expense(), "PENDING", true, null, null);
		// efetivar sem desligar o marcador também é recusado: as instruções que efetivam desligam os dois juntos
		assertThatThrownBy(() -> owner.client().sql("update transactions set status = 'CLEARED' where id = :id")
				.param("id", marked).update()).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(owner.client().sql("update transactions set status = 'CLEARED', auto_clear = false where id = :id")
				.param("id", marked).update()).isEqualTo(1);
	}

	@Test
	void oldLinesKeepTheMarkerOffAndNoOccurrenceColumns() {
		World w = world();
		UUID id = line(w, "EXPENSE", w.expense(), "CLEARED", false, null, null);

		assertThat(owner.client().sql("""
				select not auto_clear and recurrence_id is null and occurrence_number is null
				from transactions where id = :id""").param("id", id).query(Boolean.class).single()).isTrue();
		UUID defaults = owner.client().sql("""
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description, created_by)
				values (:w, :a, 'INCOME', 100, date '2026-11-05', 'Sem nada', :u) returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("u", w.fx().userId())
				.query(UUID.class).single();
		assertThat(owner.client().sql("select auto_clear from transactions where id = :id").param("id", defaults)
				.query(Boolean.class).single()).isFalse();
	}

	@Test
	void theApplicationRoleCanSwitchTheMarkerButNeverTheOccurrenceColumns() {
		World w = world();
		UUID r = recurrence(w);
		UUID id = occurrence(w, r, 0);
		UUID ws = w.fx().workspaceId();

		assertThat(asAppUpdate(w.fx().userId(), ws, "update transactions set auto_clear = true where id = :id", id)).isEqualTo(1);
		assertThat(asAppUpdate(w.fx().userId(), ws, "update transactions set auto_clear = false where id = :id", id)).isEqualTo(1);
		for (String change : new String[] { "recurrence_id = null", "recurrence_id = gen_random_uuid()",
				"occurrence_number = 5", "occurrence_number = null" }) {
			assertThatThrownBy(() -> asAppUpdate(w.fx().userId(), ws, "update transactions set " + change + " where id = :id", id))
					.as(change).rootCause().hasMessageContaining("permission denied");
		}
	}

	// ---- a ocorrência -----------------------------------------------------------------------------------------

	@Test
	void theOccurrenceColumnsComeTogetherOrNotAtAll() {
		World w = world();
		UUID r = recurrence(w);

		assertThatThrownBy(() -> line(w, "EXPENSE", w.expense(), "PENDING", false, r, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> line(w, "EXPENSE", w.expense(), "PENDING", false, null, 3))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> occurrence(w, r, -1)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(occurrence(w, r, 0)).isNotNull();
	}

	@Test
	void onlyAnIncomeOrAnExpenseIsAnOccurrenceAndNeverAnInstallmentAtTheSameTime() {
		World w = world();
		UUID r = recurrence(w);

		assertThat(line(w, "INCOME", w.income(), "PENDING", false, r, 1)).isNotNull();
		assertThatThrownBy(() -> owner.client().sql("""
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description,
				                          created_by, transfer_group_id, recurrence_id, occurrence_number)
				values (:w, :a, 'TRANSFER', -1000, date '2026-11-10', 'Transf', :u, gen_random_uuid(), :r, 2)""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("u", w.fx().userId()).param("r", r).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, recurrence_id, occurrence_number,
				                          installment_group_id, installment_number, installment_count)
				values (:w, :a, :c, 'EXPENSE', -1000, date '2026-11-10', 'Parcela', :u, :r, 3, gen_random_uuid(), 1, 3)""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", w.expense())
				.param("u", w.fx().userId()).param("r", r).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theOccurrenceNumberIsUniqueInTheRecurrenceEvenWhenTheFirstWasExcluded() {
		World w = world();
		UUID r = recurrence(w);
		UUID other = recurrence(w);
		UUID first = occurrence(w, r, 0);

		assertThatThrownBy(() -> occurrence(w, r, 0)).isInstanceOf(DataIntegrityViolationException.class);
		owner.client().sql("update transactions set deleted_at = now() where id = :id").param("id", first).update();
		// o número continua ocupado: a linha excluída segue existindo, e a ocorrência excluída não volta
		assertThatThrownBy(() -> occurrence(w, r, 0)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(occurrence(w, r, 1)).isNotNull();
		assertThat(occurrence(w, other, 0)).isNotNull();
		// linhas comuns repetem à vontade: o índice é parcial
		line(w, "EXPENSE", w.expense(), "PENDING", false, null, null);
		line(w, "EXPENSE", w.expense(), "PENDING", false, null, null);
	}

	@Test
	void anOccurrenceCannotPointToARecurrenceOfAnotherWorkspace() {
		World a = world();
		World b = world();
		UUID foreign = recurrence(b);

		assertThatThrownBy(() -> occurrence(a, foreign, 0)).isInstanceOf(DataIntegrityViolationException.class);
	}

	// ---- a tabela recurrences ---------------------------------------------------------------------------------

	private void refuse(World w, String type, UUID category, long amount, String description, String frequency,
			LocalDate start, LocalDate end) {
		assertThatThrownBy(() -> recurrence(w.fx().workspaceId(), w.fx().userId(), w.account(), category, type, amount,
				description, frequency, start, end)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theDatabaseRefusesInvalidRecurrences() {
		World w = world();

		refuse(w, "EXPENSE", w.expense(), 0, "x", "MONTHLY", START, null);
		refuse(w, "EXPENSE", w.expense(), -5, "x", "MONTHLY", START, null);
		refuse(w, "EXPENSE", w.expense(), 1_000_000_000_001L, "x", "MONTHLY", START, null);
		refuse(w, "EXPENSE", w.expense(), 100, "", "MONTHLY", START, null);
		refuse(w, "EXPENSE", w.expense(), 100, " x", "MONTHLY", START, null);
		refuse(w, "EXPENSE", w.expense(), 100, "x ", "MONTHLY", START, null);
		refuse(w, "EXPENSE", w.expense(), 100, "x".repeat(201), "MONTHLY", START, null);
		refuse(w, "EXPENSE", w.expense(), 100, "x", "DAILY", START, null);
		refuse(w, "TRANSFER", w.expense(), 100, "x", "MONTHLY", START, null);
		refuse(w, "EXPENSE", w.expense(), 100, "x", "MONTHLY", LocalDate.of(1999, 12, 31), null);
		refuse(w, "EXPENSE", w.expense(), 100, "x", "MONTHLY", LocalDate.of(2101, 1, 1), null);
		refuse(w, "EXPENSE", w.expense(), 100, "x", "MONTHLY", START, START.minusDays(1));
		refuse(w, "EXPENSE", w.expense(), 100, "x", "MONTHLY", START, LocalDate.of(2101, 1, 1));
		// o limite de cada ponta é aceito
		assertThat(recurrence(w.fx().workspaceId(), w.fx().userId(), w.account(), w.expense(), "EXPENSE", 1, "x".repeat(200),
				"WEEKLY", LocalDate.of(2000, 1, 1), LocalDate.of(2000, 1, 1))).isNotNull();
		assertThat(recurrence(w.fx().workspaceId(), w.fx().userId(), w.account(), w.expense(), "EXPENSE", 1_000_000_000_000L,
				"x", "YEARLY", LocalDate.of(2100, 12, 31), LocalDate.of(2100, 12, 31))).isNotNull();
	}

	@Test
	void theCategoryMustHaveTheRecurrenceTypeAndBelongToTheWorkspace() {
		World w = world();
		World other = world();

		// categoria de despesa numa recorrência de receita, e o contrário
		refuse(w, "INCOME", w.expense(), 100, "x", "MONTHLY", START, null);
		refuse(w, "EXPENSE", w.income(), 100, "x", "MONTHLY", START, null);
		// categoria e conta de outro workspace
		refuse(w, "EXPENSE", other.expense(), 100, "x", "MONTHLY", START, null);
		assertThatThrownBy(() -> recurrence(w.fx().workspaceId(), w.fx().userId(), other.account(), w.expense(), "EXPENSE",
				100, "x", "MONTHLY", START, null)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(recurrence(w.fx().workspaceId(), w.fx().userId(), w.account(), w.income(), "INCOME", 100, "Salário",
				"MONTHLY", START, null)).isNotNull();
	}

	@Test
	void ownersAndEditorsInsertRecurrencesAsTheApplicationRole() {
		World w = world();
		Fixture editor = fixtures().addMember(w.fx().workspaceId(), "EDITOR");

		for (Fixture who : new Fixture[] { w.fx(), editor }) {
			int inserted = asApp(who.userId(), w.fx().workspaceId(), db -> db.sql("""
					insert into recurrences (workspace_id, account_id, category_id, type, amount_cents, description,
					                         frequency, start_on, created_by)
					values (:w, :a, :c, 'EXPENSE', 100, 'Do papel', 'MONTHLY', date '2026-11-10', :u)""")
					.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", w.expense())
					.param("u", who.userId()).update());
			assertThat(inserted).isEqualTo(1);
		}
	}

	@Test
	void aViewerCannotInsertAndNobodyInsertsOnBehalfOfAnotherPersonOrWorkspace() {
		World w = world();
		World other = world();
		Fixture viewer = fixtures().addMember(w.fx().workspaceId(), "VIEWER");
		String insert = """
				insert into recurrences (workspace_id, account_id, category_id, type, amount_cents, description,
				                         frequency, start_on, created_by)
				values (:w, :a, :c, 'EXPENSE', 100, 'x', 'MONTHLY', date '2026-11-10', :u)""";

		assertThatThrownBy(() -> asApp(viewer.userId(), w.fx().workspaceId(), db -> db.sql(insert)
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", w.expense())
				.param("u", viewer.userId()).update())).rootCause().hasMessageContaining("row-level security");
		// created_by é sempre a pessoa da sessão
		assertThatThrownBy(() -> asApp(w.fx().userId(), w.fx().workspaceId(), db -> db.sql(insert)
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", w.expense())
				.param("u", UUID.randomUUID()).update())).rootCause().hasMessageContaining("row-level security");
		// e o workspace do contexto é o único em que se insere
		assertThatThrownBy(() -> asApp(w.fx().userId(), w.fx().workspaceId(), db -> db.sql(insert)
				.param("w", other.fx().workspaceId()).param("a", other.account()).param("c", other.expense())
				.param("u", w.fx().userId()).update())).rootCause().hasMessageContaining("row-level security");
	}

	@Test
	void theApplicationRoleReadsOnlyItsOwnWorkspace() {
		World a = world();
		World b = world();
		recurrence(a);
		recurrence(a);
		recurrence(b);

		long seenByA = asApp(a.fx().userId(), a.fx().workspaceId(),
				db -> db.sql("select count(*) from recurrences").query(Long.class).single());
		long noContext = new TransactionTemplate(transactionManager)
				.execute(status -> appJdbc.sql("select count(*) from recurrences").query(Long.class).single());

		assertThat(seenByA).isEqualTo(2);
		assertThat(noContext).isZero();
	}

	@Test
	void theApplicationRoleOnlyEndsARecurrenceAndNeverEditsOrDeletesIt() {
		World w = world();
		UUID r = recurrence(w);
		UUID ws = w.fx().workspaceId();

		assertThat(asAppUpdate(w.fx().userId(), ws, "update recurrences set ended_at = now(), updated_at = now() where id = :id", r))
				.isEqualTo(1);
		for (String change : new String[] { "amount_cents = 1", "frequency = 'WEEKLY'", "start_on = date '2026-12-01'",
				"end_on = date '2027-01-01'", "description = 'outra'", "auto_clear = true", "type = 'INCOME'",
				"workspace_id = gen_random_uuid()", "created_by = gen_random_uuid()" }) {
			assertThatThrownBy(() -> asAppUpdate(w.fx().userId(), ws, "update recurrences set " + change + " where id = :id", r))
					.as(change).rootCause().hasMessageContaining("permission denied");
		}
		assertThatThrownBy(() -> asAppUpdate(w.fx().userId(), ws, "delete from recurrences where id = :id", r)).rootCause()
				.hasMessageContaining("permission denied");
	}

	@Test
	void aViewerAndAnotherWorkspaceReachNoRecurrenceForUpdate() {
		World a = world();
		World b = world();
		UUID r = recurrence(a);
		Fixture viewer = fixtures().addMember(a.fx().workspaceId(), "VIEWER");
		String end = "update recurrences set ended_at = now() where id = :id";

		assertThat(asAppUpdate(viewer.userId(), a.fx().workspaceId(), end, r)).isZero();
		assertThat(asAppUpdate(b.fx().userId(), b.fx().workspaceId(), end, r)).isZero();
		assertThat(owner.client().sql("select ended_at is null from recurrences where id = :id").param("id", r)
				.query(Boolean.class).single()).isTrue();
	}

}
