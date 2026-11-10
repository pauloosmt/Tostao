package com.tostao.tostao_api.imports;

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
 * A {@code V14} vale no banco: a chave de importação tem formato, só receita e despesa avulsas a têm, é única por conta
 * (inclusive nas linhas excluídas), a aplicação a grava na criação mas nunca a altera, e o RLS por workspace a cobre.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ImportSchemaTest {

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

	private record World(Fixture fx, UUID account, UUID otherAccount, UUID expense, UUID income) {
	}

	private World world() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		Fixture fx = f.workspaceWithOwner();
		return new World(fx, f.insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false),
				f.insertAccount(fx.workspaceId(), "Poupança", "SAVINGS", 0, false),
				f.insertCategory(fx.workspaceId(), null, "Mercado", "EXPENSE", false),
				f.insertCategory(fx.workspaceId(), null, "Salário", "INCOME", false));
	}

	/** Despesa avulsa com a chave dada (nula = não importada), pela conexão do dono (sem RLS). */
	private UUID expense(World w, UUID account, String key, boolean deleted) {
		return owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, import_key, deleted_at)
				values (:w, :a, :c, 'EXPENSE', -1000, date '2026-11-10', 'Padaria', :u, :k,
				        case when :d then now() else null end)
				returning id""")
				.param("w", w.fx().workspaceId()).param("a", account).param("c", w.expense()).param("u", w.fx().userId())
				.param("k", key).param("d", deleted).query(UUID.class).single();
	}

	private UUID expense(World w, String key) {
		return expense(w, w.account(), key, false);
	}

	/** Roda como {@code tostao_app}, numa transação própria, com o contexto dado. */
	private <T> T asApp(UUID user, UUID workspace, Function<JdbcClient, T> work) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(user, workspace);
			return work.apply(appJdbc);
		});
	}

	@Test
	void theColumnExistsAndOrdinaryRowsKeepItNull() {
		World w = world();
		UUID id = expense(w, null);

		assertThat(owner.client().sql("select import_key is null from transactions where id = :id").param("id", id)
				.query(Boolean.class).single()).isTrue();
		assertThat(owner.client().sql("""
				select count(*) from information_schema.columns
				where table_name = 'transactions' and column_name = 'import_key' and is_nullable = 'YES'""")
				.query(Long.class).single()).isEqualTo(1L);
	}

	@Test
	void theKeyHasOneToTwoHundredCharactersWithoutEdgeSpaces() {
		World w = world();

		for (String bad : new String[] { "", " ", " x", "x ", "a".repeat(201) }) {
			assertThatThrownBy(() -> expense(w, bad)).as("chave <%s>".formatted(bad.length() > 10 ? "longa" : bad))
					.isInstanceOf(DataIntegrityViolationException.class);
		}
		assertThat(expense(w, "a")).isNotNull();
		assertThat(expense(w, "b".repeat(200))).isNotNull();
	}

	@Test
	void onlyAPlainIncomeOrExpenseCanCarryAKey() {
		World w = world();
		UUID group = UUID.randomUUID();
		UUID recurrence = owner.client().sql("""
				insert into recurrences (workspace_id, account_id, category_id, type, amount_cents, description,
				                         frequency, start_on, created_by)
				values (:w, :a, :c, 'EXPENSE', 1000, 'Aluguel', 'MONTHLY', date '2026-11-10', :u) returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", w.expense())
				.param("u", w.fx().userId()).query(UUID.class).single();

		// transferência
		assertThatThrownBy(() -> owner.client().sql("""
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description,
				                          created_by, transfer_group_id, import_key)
				values (:w, :a, 'TRANSFER', -1000, date '2026-11-10', 'Transf', :u, gen_random_uuid(), 'k-transfer')""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("u", w.fx().userId()).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		// parcela
		assertThatThrownBy(() -> owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, installment_group_id, installment_number,
				                          installment_count, import_key)
				values (:w, :a, :c, 'EXPENSE', -1000, date '2026-11-10', 'Notebook', :u, :g, 1, 3, 'k-parcela')""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", w.expense())
				.param("u", w.fx().userId()).param("g", group).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		// ocorrência de recorrência
		assertThatThrownBy(() -> owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, recurrence_id, occurrence_number, import_key)
				values (:w, :a, :c, 'EXPENSE', -1000, date '2026-11-10', 'Aluguel', :u, :r, 0, 'k-recorrencia')""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", w.expense())
				.param("u", w.fx().userId()).param("r", recurrence).update())
				.isInstanceOf(DataIntegrityViolationException.class);
		// receita e despesa avulsas aceitam
		assertThat(expense(w, "k-despesa")).isNotNull();
		assertThat(owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, import_key)
				values (:w, :a, :c, 'INCOME', 1000, date '2026-11-10', 'Salário', :u, 'k-receita') returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", w.income())
				.param("u", w.fx().userId()).query(UUID.class).single()).isNotNull();
	}

	@Test
	void aKeyIsUniquePerAccountEvenWhenTheFirstRowWasDeleted() {
		World w = world();
		expense(w, "id:100");

		assertThatThrownBy(() -> expense(w, "id:100")).isInstanceOf(DataIntegrityViolationException.class);
		// outra conta aceita a mesma chave
		assertThat(expense(w, w.otherAccount(), "id:100", false)).isNotNull();

		// a primeira excluída continua ocupando a chave
		expense(w, w.account(), "id:200", true);
		assertThatThrownBy(() -> expense(w, "id:200")).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void rowsWithoutAKeyNeverCollide() {
		World w = world();

		assertThat(expense(w, null)).isNotNull();
		assertThat(expense(w, null)).isNotNull();
		assertThat(owner.client().sql("select count(*) from transactions where account_id = :a and import_key is null")
				.param("a", w.account()).query(Long.class).single()).isEqualTo(2L);
	}

	@Test
	void theApplicationInsertsWithAKeyButNeverUpdatesIt() {
		World w = world();
		UUID id = expense(w, "id:original");

		int inserted = asApp(w.fx().userId(), w.fx().workspaceId(), db -> db.sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, import_key)
				values (:w, :a, :c, 'EXPENSE', -500, date '2026-11-10', 'Mercado', :u, 'id:pelo-app')""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", w.expense())
				.param("u", w.fx().userId()).update());

		assertThat(inserted).isEqualTo(1);
		assertThatThrownBy(() -> asApp(w.fx().userId(), w.fx().workspaceId(), db -> db
				.sql("update transactions set import_key = 'id:trocada' where id = :id").param("id", id).update()))
				.rootCause().hasMessageContaining("permission denied");
		// a edição comum (colunas permitidas) segue funcionando e a chave fica
		asApp(w.fx().userId(), w.fx().workspaceId(), db -> db
				.sql("update transactions set description = 'Outra' where id = :id").param("id", id).update());
		assertThat(owner.client().sql("select import_key from transactions where id = :id").param("id", id)
				.query(String.class).single()).isEqualTo("id:original");
	}

	@Test
	void theKeyOfAnotherWorkspaceIsInvisibleAndCannotBeInsertedThere() {
		World mine = world();
		World theirs = world();
		expense(theirs, "id:de-outro");

		long seen = asApp(mine.fx().userId(), mine.fx().workspaceId(),
				db -> db.sql("select count(*) from transactions where import_key = 'id:de-outro'").query(Long.class).single());

		assertThat(seen).isZero();
		assertThatThrownBy(() -> asApp(mine.fx().userId(), mine.fx().workspaceId(), db -> db.sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, import_key)
				values (:w, :a, :c, 'EXPENSE', -500, date '2026-11-10', 'Mercado', :u, 'id:cruzada')""")
				.param("w", theirs.fx().workspaceId()).param("a", theirs.account()).param("c", theirs.expense())
				.param("u", mine.fx().userId()).update())).rootCause().hasMessageContaining("row-level security");
	}

}
