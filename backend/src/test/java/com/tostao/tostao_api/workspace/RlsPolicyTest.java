package com.tostao.tostao_api.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;

/**
 * Camada de dados, sozinha: conecta como {@code tostao_app} (a role da aplicação, sem BYPASSRLS) e
 * consulta sem nenhum {@code WHERE} de workspace. Os dados são preparados pela conexão do dono.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RlsPolicyTest {

	@Autowired
	DataSource appDataSource;
	@Autowired
	OwnerJdbc owner;

	UUID userA;
	UUID userB;
	UUID workspaceA;
	UUID workspaceB;

	@BeforeEach
	void seed() {
		userA = newUser();
		userB = newUser();
		workspaceA = newWorkspaceWithData(userA, "A");
		workspaceB = newWorkspaceWithData(userB, "B");
	}

	private UUID newUser() {
		return owner.client()
				.sql("insert into users (email, password_hash, name) values (:e, 'x', 'T') returning id")
				.param("e", "rls-" + UUID.randomUUID() + "@example.com").query(UUID.class).single();
	}

	private UUID newWorkspaceWithData(UUID user, String label) {
		var db = owner.client();
		UUID ws = db.sql("insert into workspaces (name) values (:n) returning id").param("n", label)
				.query(UUID.class).single();
		db.sql("insert into workspace_members (workspace_id, user_id, role) values (:w, :u, 'OWNER')")
				.param("w", ws).param("u", user).update();
		UUID account = db.sql("""
				insert into accounts (workspace_id, name, type) values (:w, :n, 'CHECKING') returning id""")
				.param("w", ws).param("n", "Conta " + label).query(UUID.class).single();
		db.sql("insert into categories (workspace_id, name, kind) values (:w, :n, 'EXPENSE')")
				.param("w", ws).param("n", "Cat " + label).update();
		db.sql("""
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description, created_by)
				values (:w, :a, 'EXPENSE', -1000, current_date, 'compra', :u)""")
				.param("w", ws).param("a", account).param("u", user).update();
		return ws;
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

	@Test
	void withoutContextTheApplicationRoleSeesNothing() throws Exception {
		try (Connection c = tx()) {
			for (String table : new String[] { "accounts", "categories", "transactions", "workspaces",
					"workspace_members", "users" }) {
				assertThat(count(c, "select count(*) from " + table)).as(table).isZero();
			}
			c.rollback();
		}
	}

	@Test
	void withWorkspaceContextOnlyThatWorkspacesRowsAreVisible() throws Exception {
		try (Connection c = tx()) {
			setContext(c, userA, workspaceA);

			assertThat(count(c, "select count(*) from accounts")).isEqualTo(1);
			assertThat(count(c, "select count(*) from categories")).isEqualTo(1);
			assertThat(count(c, "select count(*) from transactions")).isEqualTo(1);
			assertThat(count(c, "select count(*) from accounts where workspace_id = '" + workspaceB + "'")).isZero();
			assertThat(count(c, "select count(*) from transactions where workspace_id = '" + workspaceB + "'"))
					.isZero();
			c.rollback();
		}
	}

	@Test
	void userContextSeesOnlyOwnUserMembershipsAndWorkspaces() throws Exception {
		try (Connection c = tx()) {
			setContext(c, userA, null);

			assertThat(count(c, "select count(*) from users")).isEqualTo(1);
			assertThat(count(c, "select count(*) from users where id = '" + userB + "'")).isZero();
			assertThat(count(c, "select count(*) from workspace_members")).isEqualTo(1);
			assertThat(count(c, "select count(*) from workspaces")).isEqualTo(1);
			assertThat(count(c, "select count(*) from workspaces where id = '" + workspaceB + "'")).isZero();
			// sem contexto de workspace, dados de workspace continuam invisíveis
			assertThat(count(c, "select count(*) from accounts")).isZero();
			c.rollback();
		}
	}

	@Test
	void insertingIntoAnotherWorkspaceIsRejectedByWithCheck() throws Exception {
		try (Connection c = tx(); Statement st = c.createStatement()) {
			setContext(c, userA, workspaceA);

			assertThatThrownBy(() -> st.executeUpdate(
					"insert into accounts (workspace_id, name, type) values ('" + workspaceB + "', 'invasora', 'CASH')"))
					.isInstanceOf(SQLException.class)
					.hasMessageContaining("row-level security");
		}
	}

	@Test
	void updatingAndDeletingAnotherWorkspacesRowsAffectsNothing() throws Exception {
		try (Connection c = tx(); Statement st = c.createStatement()) {
			setContext(c, userA, workspaceA);

			int updated = st.executeUpdate("update accounts set name = 'hackeada' where workspace_id = '" + workspaceB + "'");
			int deleted = st.executeUpdate("delete from transactions where workspace_id = '" + workspaceB + "'");

			assertThat(updated).isZero();
			assertThat(deleted).isZero();
			c.rollback();
		}
		long untouched = owner.client().sql("select count(*) from accounts where workspace_id = :w and name = 'Conta B'")
				.param("w", workspaceB).query(Long.class).single();
		assertThat(untouched).isEqualTo(1);
	}

	@Test
	void contextDoesNotLeakToTheNextTransactionOnTheSameConnection() throws Exception {
		try (Connection c = tx()) {
			setContext(c, userA, workspaceA);
			assertThat(count(c, "select count(*) from accounts")).isEqualTo(1);
			c.commit();

			// segunda transação, mesma conexão (como o pool reaproveita)
			assertThat(count(c, "select count(*) from accounts")).isZero();
			try (Statement st = c.createStatement();
					ResultSet rs = st.executeQuery("select nullif(current_setting('app.workspace_id', true), '')")) {
				rs.next();
				assertThat(rs.getString(1)).isNull();
			}
			c.rollback();
		}
	}

	@Test
	void directInsertsIntoIdentityTablesAreDenied() throws Exception {
		String[] statements = {
				"insert into users (email, password_hash, name) values ('x@x.com', 'h', 'n')",
				"insert into workspaces (name) values ('invasora')",
				"insert into workspace_members (workspace_id, user_id, role) values ('" + workspaceB + "', '" + userA + "', 'OWNER')" };
		for (String sql : statements) {
			try (Connection c = tx(); Statement st = c.createStatement()) {
				setContext(c, userA, workspaceA);
				assertThatThrownBy(() -> st.executeUpdate(sql)).as(sql).isInstanceOf(SQLException.class)
						.hasMessageContaining("permission denied");
			}
		}
	}

	@Test
	void passwordHashAndSessionVersionAreNotReadableByTheApplicationRole() throws Exception {
		for (String sql : new String[] { "select password_hash from users", "select session_version from users",
				"select * from users" }) {
			try (Connection c = tx(); Statement st = c.createStatement()) {
				setContext(c, userA, workspaceA);
				assertThatThrownBy(() -> st.executeQuery(sql)).as(sql).isInstanceOf(SQLException.class)
						.hasMessageContaining("permission denied");
			}
		}
	}

	@Test
	void loginAttemptsAreNotReadableDirectly() throws Exception {
		try (Connection c = tx(); Statement st = c.createStatement()) {
			assertThatThrownBy(() -> st.executeQuery("select * from login_attempts"))
					.isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
		}
	}

}
