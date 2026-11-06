package com.tostao.tostao_api.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * Camada de dados dos lançamentos, sozinha: conecta como {@code tostao_app} e escreve direto no banco, sem
 * passar por serviço nenhum (a role da aplicação não ignora o RLS e tem privilégios estreitos).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransactionRlsTest {

	@Autowired
	DataSource appDataSource;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private Connection tx() throws SQLException {
		Connection c = appDataSource.getConnection();
		c.setAutoCommit(false);
		return c;
	}

	private static void setContext(Connection c, UUID user, UUID workspace) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(
				"select set_config('app.user_id', ?, true), set_config('app.workspace_id', ?, true)")) {
			ps.setString(1, user == null ? "" : user.toString());
			ps.setString(2, workspace == null ? "" : workspace.toString());
			ps.executeQuery();
		}
	}

	private static long count(Connection c, String sql) throws SQLException {
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	/** Executa o comando numa transação própria, com o contexto dado, e devolve as linhas afetadas. */
	private int run(UUID user, UUID workspace, String sql) throws SQLException {
		try (Connection c = tx(); Statement st = c.createStatement()) {
			setContext(c, user, workspace);
			int rows = st.executeUpdate(sql);
			c.commit();
			return rows;
		}
	}

	private record World(Fixture owner, UUID account, UUID category, UUID existing) {

		String insert(UUID workspace, UUID createdBy, String description) {
			return "insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on, "
					+ "description, created_by) values ('" + workspace + "', '" + account + "', '" + category
					+ "', 'EXPENSE', -100, current_date, '" + description + "', '" + createdBy + "')";
		}
	}

	private World world() {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		UUID w = ownerFx.workspaceId();
		UUID account = fixtures().insertAccount(w, "Corrente", "CHECKING", 0, false);
		UUID category = fixtures().insertCategory(w, null, "Mercado", "EXPENSE", false);
		UUID existing = fixtures().insertLedgerTransaction(w, account, ownerFx.userId(), category, "EXPENSE", 500,
				LocalDate.of(2026, 11, 1), "Existente", false);
		return new World(ownerFx, account, category, existing);
	}

	private String descriptionOf(UUID id) {
		return owner.client().sql("select description from transactions where id = :id").param("id", id)
				.query(String.class).single();
	}

	@Test
	void viewerCanReadButNeitherInsertNorUpdate() throws Exception {
		World wd = world();
		UUID w = wd.owner().workspaceId();
		Fixture viewer = fixtures().addMember(w, "VIEWER");

		try (Connection c = tx()) {
			setContext(c, viewer.userId(), w);
			assertThat(count(c, "select count(*) from transactions")).isEqualTo(1);
			c.rollback();
		}
		assertThatThrownBy(() -> run(viewer.userId(), w, wd.insert(w, viewer.userId(), "Do viewer")))
				.hasMessageContaining("row-level security");
		assertThat(run(viewer.userId(), w, "update transactions set description = 'Mudada' where id = '"
				+ wd.existing() + "'")).isZero();
		assertThat(descriptionOf(wd.existing())).isEqualTo("Existente");
	}

	@Test
	void editorAndOwnerCanInsertAndUpdateTheEditableColumns() throws Exception {
		World wd = world();
		UUID w = wd.owner().workspaceId();
		Fixture editor = fixtures().addMember(w, "EDITOR");

		assertThat(run(editor.userId(), w, wd.insert(w, editor.userId(), "Do editor"))).isEqualTo(1);
		assertThat(run(wd.owner().userId(), w, wd.insert(w, wd.owner().userId(), "Do dono"))).isEqualTo(1);
		assertThat(run(editor.userId(), w, "update transactions set description = 'Editada', amount_cents = -900, "
				+ "notes = 'obs', occurred_on = date '2026-10-10', updated_at = now() where id = '" + wd.existing()
				+ "'")).isEqualTo(1);
		assertThat(run(wd.owner().userId(), w, "update transactions set deleted_at = now() where id = '"
				+ wd.existing() + "'")).isEqualTo(1);
	}

	@Test
	void withoutContextNothingIsVisibleNorWritable() throws Exception {
		World wd = world();

		try (Connection c = tx()) {
			assertThat(count(c, "select count(*) from transactions")).isZero();
			c.rollback();
		}
		assertThatThrownBy(() -> run(null, null, wd.insert(wd.owner().workspaceId(), wd.owner().userId(), "Sem contexto")))
				.hasMessageContaining("row-level security");
	}

	@Test
	void aWorkspaceContextNeitherSeesNorChangesAnotherWorkspacesTransactions() throws Exception {
		World a = world();
		World b = world();
		UUID wa = a.owner().workspaceId();
		UUID wb = b.owner().workspaceId();

		try (Connection c = tx()) {
			setContext(c, a.owner().userId(), wa);
			assertThat(count(c, "select count(*) from transactions")).isEqualTo(1);
			assertThat(count(c, "select count(*) from transactions where workspace_id = '" + wb + "'")).isZero();
			c.rollback();
		}
		assertThat(run(a.owner().userId(), wa, "update transactions set description = 'Invadida' where id = '"
				+ b.existing() + "'")).isZero();
		assertThatThrownBy(() -> run(a.owner().userId(), wa, b.insert(wb, a.owner().userId(), "Invasora")))
				.hasMessageContaining("row-level security");
		assertThat(descriptionOf(b.existing())).isEqualTo("Existente");
	}

	@Test
	void createdByMustBeTheSessionUser() {
		World wd = world();
		UUID w = wd.owner().workspaceId();
		Fixture editor = fixtures().addMember(w, "EDITOR");

		assertThatThrownBy(() -> run(editor.userId(), w, wd.insert(w, wd.owner().userId(), "Em nome do dono")))
				.hasMessageContaining("row-level security");
		assertThatThrownBy(() -> run(wd.owner().userId(), w, wd.insert(w, UUID.randomUUID(), "Em nome de ninguem")))
				.hasMessageContaining("row-level security");
	}

	@Test
	void nobodyCanDeleteATransactionNotEvenTheOwner() {
		World wd = world();

		assertThatThrownBy(() -> run(wd.owner().userId(), wd.owner().workspaceId(),
				"delete from transactions where id = '" + wd.existing() + "'"))
				.hasMessageContaining("permission denied");
		assertThat(owner.client().sql("select count(*) from transactions where id = :id").param("id", wd.existing())
				.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void theImmutableColumnsCannotBeUpdated() {
		World wd = world();
		UUID w = wd.owner().workspaceId();
		String where = " where id = '" + wd.existing() + "'";

		for (String change : new String[] { "created_by = '" + UUID.randomUUID() + "'", "type = 'INCOME'",
				"workspace_id = '" + UUID.randomUUID() + "'", "transfer_group_id = '" + UUID.randomUUID() + "'",
				"created_at = now()", "status = 'PENDING'", "id = '" + UUID.randomUUID() + "'" }) {
			assertThatThrownBy(() -> run(wd.owner().userId(), w, "update transactions set " + change + where))
					.as(change).hasMessageContaining("permission denied");
		}
	}

	@Test
	void anAccountOrCategoryOfAnotherWorkspaceIsRejectedByTheCompositeKeys() {
		World a = world();
		World b = world();
		UUID wa = a.owner().workspaceId();

		String foreignAccount = "insert into transactions (workspace_id, account_id, category_id, type, amount_cents, "
				+ "occurred_on, description, created_by) values ('" + wa + "', '" + b.account() + "', '" + a.category()
				+ "', 'EXPENSE', -100, current_date, 'x', '" + a.owner().userId() + "')";
		String foreignCategory = "insert into transactions (workspace_id, account_id, category_id, type, amount_cents, "
				+ "occurred_on, description, created_by) values ('" + wa + "', '" + a.account() + "', '" + b.category()
				+ "', 'EXPENSE', -100, current_date, 'x', '" + a.owner().userId() + "')";

		assertThatThrownBy(() -> run(a.owner().userId(), wa, foreignAccount)).hasMessageContaining("foreign key");
		assertThatThrownBy(() -> run(a.owner().userId(), wa, foreignCategory)).hasMessageContaining("foreign key");
	}

}
