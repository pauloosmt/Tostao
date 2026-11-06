package com.tostao.tostao_api.category;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A V4 sobre um banco que já está na V3: workspaces sem nenhuma categoria recebem o conjunto padrão uma
 * única vez, e os que já têm categorias não recebem nem duplicam nada (FR-003).
 */
class CategoriesMigrationTest {

	private static Flyway flyway(PostgreSQLContainer db, String target) {
		return Flyway.configure()
				.dataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword())
				.locations("classpath:db/migration")
				.placeholders(Map.of("app_password", "migration_test_pw"))
				.target(target)
				.load();
	}

	@Test
	void backfillsOnlyWorkspacesWithoutCategoriesAndOnlyOnce() {
		try (PostgreSQLContainer db = new PostgreSQLContainer(DockerImageName.parse("postgres:17"))) {
			db.start();
			flyway(db, "3").migrate();
			JdbcClient jdbc = JdbcClient.create(
					new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword()));
			UUID empty = jdbc.sql("insert into workspaces (name) values ('Vazio') returning id")
					.query(UUID.class).single();
			UUID withOne = jdbc.sql("insert into workspaces (name) values ('Com uma') returning id")
					.query(UUID.class).single();
			jdbc.sql("insert into categories (workspace_id, name, kind) values (:w, 'Minha', 'EXPENSE')")
					.param("w", withOne).update();

			flyway(db, "latest").migrate();

			assertThat(count(jdbc, empty)).isEqualTo(20);
			assertThat(count(jdbc, withOne)).isEqualTo(1);
			assertThat(jdbc.sql("select name from categories where workspace_id = :w").param("w", withOne)
					.query(String.class).single()).isEqualTo("Minha");

			// Migrar de novo não faz nada: a V4 já está aplicada e o conjunto não volta.
			jdbc.sql("update categories set archived = true where workspace_id = :w and name = 'Lazer'")
					.param("w", empty).update();
			flyway(db, "latest").migrate();
			assertThat(count(jdbc, empty)).isEqualTo(20);
			assertThat(jdbc.sql("select count(*) from categories where workspace_id = :w and archived")
					.param("w", empty).query(Long.class).single()).isEqualTo(1);
		}
	}

	private static long count(JdbcClient jdbc, UUID workspace) {
		return jdbc.sql("select count(*) from categories where workspace_id = :w").param("w", workspace)
				.query(Long.class).single();
	}

}
