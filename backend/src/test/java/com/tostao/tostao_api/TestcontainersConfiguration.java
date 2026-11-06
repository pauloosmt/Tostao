package com.tostao.tostao_api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres real para os testes. O Flyway conecta como dono do schema (usuário do container) e a
 * aplicação conecta como {@code tostao_app}, sem BYPASSRLS, igual à produção. Para preparar e
 * inspecionar dados ignorando o RLS, os testes usam o bean {@code OwnerJdbc}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final String APP_PASSWORD = "tostao_app_test";
	public static final String JWT_SECRET = "test-only-secret-with-at-least-32-bytes-0123456789";

	@Bean
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
	}

	@Bean
	DynamicPropertyRegistrar databaseProperties(PostgreSQLContainer postgres) {
		return registry -> {
			registry.add("spring.flyway.url", postgres::getJdbcUrl);
			registry.add("spring.flyway.user", postgres::getUsername);
			registry.add("spring.flyway.password", postgres::getPassword);
			registry.add("spring.flyway.placeholders.app_password", () -> APP_PASSWORD);
			registry.add("spring.datasource.url", postgres::getJdbcUrl);
			registry.add("spring.datasource.username", () -> "tostao_app");
			registry.add("spring.datasource.password", () -> APP_PASSWORD);
			registry.add("tostao.security.jwt-secret", () -> JWT_SECRET);
			registry.add("tostao.security.cookie-secure", () -> "false");
		};
	}

	@Bean
	OwnerJdbc ownerJdbc(PostgreSQLContainer postgres) {
		return new OwnerJdbc(JdbcClient.create(new DriverManagerDataSource(
				postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())));
	}

	/**
	 * Conexão do dono do schema (ignora RLS). É um tipo próprio, e não um {@code JdbcClient}, para não
	 * desligar o {@code JdbcClient} da aplicação, que conecta como {@code tostao_app}.
	 */
	public record OwnerJdbc(JdbcClient client) {
	}

}
