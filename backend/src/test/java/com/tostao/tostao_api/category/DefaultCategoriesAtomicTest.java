package com.tostao.tostao_api.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.tostao.tostao_api.TestcontainersConfiguration;

/**
 * Se a semente das categorias falhar, o cadastro inteiro volta (FR-002, SC-001): nada de usuário nem de
 * workspace sem o conjunto padrão. Tudo roda numa transação do dono que termina em rollback.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DefaultCategoriesAtomicTest {

	@Autowired
	PostgreSQLContainer postgres;

	private static long count(Connection c, String sql) throws SQLException {
		try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	@Test
	void aFailingSeedUndoesTheWholeRegistration() throws Exception {
		try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword())) {
			c.setAutoCommit(false);
			try (Statement st = c.createStatement()) {
				st.execute("""
						create function pg_temp.fail_seed() returns trigger language plpgsql as
						$$ begin raise exception 'semente forçada a falhar'; end $$""");
				st.execute("""
						create trigger fail_seed before insert on categories
						for each row execute function pg_temp.fail_seed()""");
				long usersBefore = count(c, "select count(*) from users");
				long workspacesBefore = count(c, "select count(*) from workspaces");
				Savepoint before = c.setSavepoint();

				assertThatThrownBy(() -> {
					try (Statement call = c.createStatement()) {
						call.execute("select * from register_user('atomico@example.com', 'x', 'Atomico')");
					}
				}).hasMessageContaining("semente forçada a falhar");
				c.rollback(before);

				assertThat(count(c, "select count(*) from users where email = 'atomico@example.com'")).isZero();
				assertThat(count(c, "select count(*) from users")).isEqualTo(usersBefore);
				assertThat(count(c, "select count(*) from workspaces")).isEqualTo(workspacesBefore);
			}
			finally {
				c.rollback();
			}
		}
	}

}
