package com.tostao.tostao_api.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;

/** Guard-rail: toda tabela nova precisa nascer com RLS, e a role da aplicação não pode ignorá-lo. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RlsCoverageTest {

	@Autowired
	OwnerJdbc owner;

	@Test
	void everyPublicTableHasRowLevelSecurityEnabled() {
		List<String> withoutRls = owner.client().sql("""
				select c.relname
				from pg_class c
				join pg_namespace n on n.oid = c.relnamespace
				where n.nspname = 'public' and c.relkind = 'r'
				  and c.relname <> 'flyway_schema_history'
				  and not c.relrowsecurity
				order by c.relname
				""").query(String.class).list();

		assertThat(withoutRls).as("tabelas em public sem RLS ligado").isEmpty();
	}

	@Test
	void applicationRoleCannotBypassRowLevelSecurity() {
		var role = owner.client()
				.sql("select rolbypassrls, rolsuper, rolcanlogin from pg_roles where rolname = 'tostao_app'")
				.query((rs, i) -> new boolean[] { rs.getBoolean(1), rs.getBoolean(2), rs.getBoolean(3) })
				.single();

		assertThat(role[0]).as("rolbypassrls").isFalse();
		assertThat(role[1]).as("rolsuper").isFalse();
		assertThat(role[2]).as("rolcanlogin").isTrue();
	}

	@Test
	void applicationRoleDoesNotOwnAnyTable() {
		List<String> owned = owner.client().sql("""
				select tablename from pg_tables
				where schemaname = 'public' and tableowner = 'tostao_app'
				""").query(String.class).list();

		assertThat(owned).isEmpty();
	}

}
