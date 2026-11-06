package com.tostao.tostao_api.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Camada de dados das categorias, sozinha: conecta como {@code tostao_app} e escreve direto no banco,
 * sem passar por serviço nenhum (a role da aplicação não ignora o RLS).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CategoryRlsTest {

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

	private static String insertSql(UUID workspace, String name) {
		return "insert into categories (workspace_id, name, kind) values ('" + workspace + "', '" + name
				+ "', 'EXPENSE')";
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

	@Test
	void viewerCannotInsertOrUpdateButCanRead() throws Exception {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture viewer = fixtures().addMember(ownerFx.workspaceId(), "VIEWER");
		UUID existing = fixtures().insertCategory(ownerFx.workspaceId(), null, "Existente", "EXPENSE", false);

		try (Connection c = tx()) {
			setContext(c, viewer.userId(), ownerFx.workspaceId());
			assertThat(count(c, "select count(*) from categories")).isEqualTo(1);
			c.rollback();
		}
		assertThatThrownBy(() -> run(viewer.userId(), ownerFx.workspaceId(), insertSql(ownerFx.workspaceId(), "Nova")))
				.hasMessageContaining("row-level security");
		int updated = run(viewer.userId(), ownerFx.workspaceId(),
				"update categories set name = 'Mudada' where id = '" + existing + "'");
		assertThat(updated).isZero();
		assertThat(owner.client().sql("select name from categories where id = :id").param("id", existing)
				.query(String.class).single()).isEqualTo("Existente");
	}

	@Test
	void editorAndOwnerCanInsertAndUpdate() throws Exception {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture editor = fixtures().addMember(ownerFx.workspaceId(), "EDITOR");
		UUID existing = fixtures().insertCategory(ownerFx.workspaceId(), null, "Existente", "EXPENSE", false);

		assertThat(run(editor.userId(), ownerFx.workspaceId(), insertSql(ownerFx.workspaceId(), "Do editor")))
				.isEqualTo(1);
		assertThat(run(editor.userId(), ownerFx.workspaceId(),
				"update categories set name = 'Editada' where id = '" + existing + "'")).isEqualTo(1);
		assertThat(run(ownerFx.userId(), ownerFx.workspaceId(), insertSql(ownerFx.workspaceId(), "Do dono")))
				.isEqualTo(1);
		assertThat(run(ownerFx.userId(), ownerFx.workspaceId(),
				"update categories set archived = true where id = '" + existing + "'")).isEqualTo(1);
	}

	@Test
	void withoutContextNothingIsVisible() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		fixtures().insertCategory(fx.workspaceId(), null, "Existente", "EXPENSE", false);

		try (Connection c = tx()) {
			assertThat(count(c, "select count(*) from categories")).isZero();
			c.rollback();
		}
		assertThatThrownBy(() -> run(null, null, insertSql(fx.workspaceId(), "Sem contexto")))
				.hasMessageContaining("row-level security");
	}

	@Test
	void aWorkspaceContextNeitherSeesNorChangesAnotherWorkspacesCategories() throws Exception {
		Fixture a = fixtures().workspaceWithOwner();
		Fixture b = fixtures().workspaceWithOwner();
		fixtures().insertCategory(a.workspaceId(), null, "De A", "EXPENSE", false);
		UUID inB = fixtures().insertCategory(b.workspaceId(), null, "De B", "EXPENSE", false);

		try (Connection c = tx()) {
			setContext(c, a.userId(), a.workspaceId());
			assertThat(count(c, "select count(*) from categories")).isEqualTo(1);
			assertThat(count(c, "select count(*) from categories where workspace_id = '" + b.workspaceId() + "'"))
					.isZero();
			c.rollback();
		}
		assertThat(run(a.userId(), a.workspaceId(), "update categories set name = 'Invadida' where id = '" + inB + "'"))
				.isZero();
		assertThatThrownBy(() -> run(a.userId(), a.workspaceId(), insertSql(b.workspaceId(), "Invasora")))
				.hasMessageContaining("row-level security");
		assertThat(owner.client().sql("select name from categories where id = :id").param("id", inB)
				.query(String.class).single()).isEqualTo("De B");
	}

	@Test
	void nobodyCanDeleteACategoryNotEvenTheOwner() {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID category = fixtures().insertCategory(fx.workspaceId(), null, "Permanente", "EXPENSE", false);

		assertThatThrownBy(() -> run(fx.userId(), fx.workspaceId(), "delete from categories where id = '" + category + "'"))
				.hasMessageContaining("permission denied");
		assertThat(owner.client().sql("select count(*) from categories where id = :id").param("id", category)
				.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void aSubcategoryCannotPointToAParentOfAnotherWorkspace() {
		Fixture a = fixtures().workspaceWithOwner();
		Fixture b = fixtures().workspaceWithOwner();
		UUID parentInB = fixtures().insertCategory(b.workspaceId(), null, "Pai de B", "EXPENSE", false);

		String sql = "insert into categories (workspace_id, parent_id, name, kind) values ('" + a.workspaceId()
				+ "', '" + parentInB + "', 'Filha', 'EXPENSE')";

		assertThatThrownBy(() -> run(a.userId(), a.workspaceId(), sql)).hasMessageContaining("foreign key");
	}

}
