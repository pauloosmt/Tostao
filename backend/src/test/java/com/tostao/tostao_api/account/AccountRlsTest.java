package com.tostao.tostao_api.account;

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
import org.springframework.dao.DataIntegrityViolationException;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * Camada de dados das contas, sozinha: conecta como {@code tostao_app} e escreve direto no banco,
 * sem passar por serviço nenhum. As restrições da V3 são provadas com a conexão do dono.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AccountRlsTest {

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
		return "insert into accounts (workspace_id, name, type, initial_balance_cents) values ('" + workspace
				+ "', '" + name + "', 'CHECKING', 0)";
	}

	@Test
	void viewerCannotInsertOrUpdateButCanRead() throws Exception {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture viewer = fixtures().addMember(ownerFx.workspaceId(), "VIEWER");
		fixtures().insertAccount(ownerFx.workspaceId(), "Existente", "CHECKING", 0, false);

		try (Connection c = tx(); Statement st = c.createStatement()) {
			setContext(c, viewer.userId(), ownerFx.workspaceId());
			assertThat(count(c, "select count(*) from accounts")).isEqualTo(1);
			assertThatThrownBy(() -> st.executeUpdate(insertSql(ownerFx.workspaceId(), "Invasora")))
					.isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
		}
		try (Connection c = tx(); Statement st = c.createStatement()) {
			setContext(c, viewer.userId(), ownerFx.workspaceId());
			assertThat(st.executeUpdate("update accounts set name = 'Hackeada'")).isZero();
			c.rollback();
		}
		long renamed = owner.client().sql("select count(*) from accounts where workspace_id = :w and name = 'Hackeada'")
				.param("w", ownerFx.workspaceId()).query(Long.class).single();
		assertThat(renamed).isZero();
	}

	@Test
	void editorAndOwnerCanInsertAndUpdate() throws Exception {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture editor = fixtures().addMember(ownerFx.workspaceId(), "EDITOR");
		fixtures().insertAccount(ownerFx.workspaceId(), "Existente", "CHECKING", 0, false);

		for (Fixture who : new Fixture[] { ownerFx, editor }) {
			try (Connection c = tx(); Statement st = c.createStatement()) {
				setContext(c, who.userId(), ownerFx.workspaceId());
				assertThat(st.executeUpdate(insertSql(ownerFx.workspaceId(), "Nova " + who.userId()))).isEqualTo(1);
				assertThat(st.executeUpdate("update accounts set updated_at = now() where name = 'Existente'"))
						.isEqualTo(1);
				c.rollback();
			}
		}
	}

	@Test
	void withoutContextNothingIsVisible() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		fixtures().insertAccount(fx.workspaceId(), "Escondida", "CHECKING", 0, false);

		try (Connection c = tx()) {
			assertThat(count(c, "select count(*) from accounts")).isZero();
			c.rollback();
		}
	}

	@Test
	void aWorkspaceContextNeitherSeesNorChangesAnotherWorkspacesAccounts() throws Exception {
		Fixture a = fixtures().workspaceWithOwner();
		Fixture b = fixtures().workspaceWithOwner();
		fixtures().insertAccount(a.workspaceId(), "Conta A", "CHECKING", 0, false);
		fixtures().insertAccount(b.workspaceId(), "Conta B", "CHECKING", 0, false);

		try (Connection c = tx(); Statement st = c.createStatement()) {
			setContext(c, a.userId(), a.workspaceId());
			assertThat(count(c, "select count(*) from accounts")).isEqualTo(1);
			assertThat(count(c, "select count(*) from accounts where workspace_id = '" + b.workspaceId() + "'"))
					.isZero();
			assertThat(st.executeUpdate("update accounts set name = 'x' where workspace_id = '" + b.workspaceId() + "'"))
					.isZero();
			assertThatThrownBy(() -> st.executeUpdate(insertSql(b.workspaceId(), "Invasora")))
					.isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
		}
	}

	@Test
	void noRoleCanDeleteAnAccount() throws Exception {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		fixtures().insertAccount(ownerFx.workspaceId(), "Permanente", "CHECKING", 0, false);

		try (Connection c = tx(); Statement st = c.createStatement()) {
			setContext(c, ownerFx.userId(), ownerFx.workspaceId());
			assertThatThrownBy(() -> st.executeUpdate("delete from accounts")).isInstanceOf(SQLException.class)
					.hasMessageContaining("permission denied");
		}
	}

	@Test
	void cardFieldsOnANonCardAreRefusedByTheDatabase() {
		Fixture fx = fixtures().workspaceWithOwner();

		assertThatThrownBy(() -> owner.client().sql("""
				insert into accounts (workspace_id, name, type, initial_balance_cents, credit_limit_cents)
				values (:w, 'Corrente com limite', 'CHECKING', 0, 1000)""").param("w", fx.workspaceId()).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("accounts_card_fields_only_on_cards");
		assertThatThrownBy(() -> owner.client().sql("""
				insert into accounts (workspace_id, name, type, initial_balance_cents, closing_day)
				values (:w, 'Poupança com dia', 'SAVINGS', 0, 5)""").param("w", fx.workspaceId()).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("accounts_card_fields_only_on_cards");
	}

	@Test
	void valuesAboveTheLimitAreRefusedByTheDatabase() {
		Fixture fx = fixtures().workspaceWithOwner();

		assertThatThrownBy(() -> fixtures().insertAccount(fx.workspaceId(), "Enorme", "CHECKING",
				1_000_000_000_001L, false)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("accounts_amount_limits");
		assertThatThrownBy(() -> fixtures().insertAccount(fx.workspaceId(), "Enorme negativa", "CHECKING",
				-1_000_000_000_001L, false)).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("accounts_amount_limits");
		assertThatThrownBy(() -> owner.client().sql("""
				insert into accounts (workspace_id, name, type, initial_balance_cents, credit_limit_cents)
				values (:w, 'Limite enorme', 'CREDIT_CARD', 0, 1000000000001)""")
				.param("w", fx.workspaceId()).update()).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("accounts_amount_limits");
		assertThat(fixtures().insertAccount(fx.workspaceId(), "No limite", "CHECKING", 1_000_000_000_000L, false))
				.isNotNull();
	}

	@Test
	void untrimmedAndOverlongNamesAreRefusedByTheDatabase() {
		Fixture fx = fixtures().workspaceWithOwner();

		assertThatThrownBy(() -> fixtures().insertAccount(fx.workspaceId(), " com espaço", "CHECKING", 0, false))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("accounts_name_trimmed");
		assertThatThrownBy(() -> fixtures().insertAccount(fx.workspaceId(), "n".repeat(101), "CHECKING", 0, false))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("accounts_name_trimmed");
		assertThatThrownBy(() -> fixtures().insertAccount(fx.workspaceId(), "", "CHECKING", 0, false))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("accounts_name_trimmed");
	}

	@Test
	void activeNamesAreUniquePerWorkspaceIgnoringCase() {
		Fixture one = fixtures().workspaceWithOwner();
		Fixture two = fixtures().workspaceWithOwner();
		fixtures().insertAccount(one.workspaceId(), "Nubank", "CHECKING", 0, false);

		assertThatThrownBy(() -> fixtures().insertAccount(one.workspaceId(), "NUBANK", "SAVINGS", 0, false))
				.hasMessageContaining("accounts_active_name_key");
		// outro workspace pode ter o mesmo nome, e uma conta arquivada não ocupa o nome
		assertThat(fixtures().insertAccount(two.workspaceId(), "Nubank", "CHECKING", 0, false)).isNotNull();
		assertThat(fixtures().insertAccount(one.workspaceId(), "Antiga", "CHECKING", 0, true)).isNotNull();
		assertThat(fixtures().insertAccount(one.workspaceId(), "antiga", "CHECKING", 0, false)).isNotNull();
	}

}
