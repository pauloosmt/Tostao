package com.tostao.tostao_api.category;

import static com.tostao.tostao_api.support.AuthSupport.PASSWORD;
import static com.tostao.tostao_api.support.AuthSupport.newEmail;
import static com.tostao.tostao_api.support.AuthSupport.register;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.support.AuthSupport;

import com.jayway.jsonpath.JsonPath;

/** O cadastro entrega o workspace pessoal com o conjunto padrão de categorias (FR-001 a FR-004). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DefaultCategoriesTest {

	private static final Set<String> EXPENSE_ROOTS = Set.of("Alimentação", "Moradia", "Transporte", "Saúde", "Lazer",
			"Educação", "Outros");
	private static final Set<String> INCOME_ROOTS = Set.of("Salário", "Rendimentos", "Outros");

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	JdbcClient appJdbc;
	private UUID registerAndGetWorkspace() throws Exception {
		MvcResult result = register(mvc, newEmail());
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.workspaces[0].id"));
	}

	private long count(UUID workspaceId, String where) {
		return owner.client().sql("select count(*) from categories where workspace_id = :w and " + where)
				.param("w", workspaceId).query(Long.class).single();
	}

	@Test
	void registrationCreatesTheWholeDefaultSet() throws Exception {
		UUID workspace = registerAndGetWorkspace();

		assertThat(count(workspace, "parent_id is null")).isEqualTo(10);
		assertThat(count(workspace, "parent_id is null and kind = 'EXPENSE'")).isEqualTo(7);
		assertThat(count(workspace, "parent_id is null and kind = 'INCOME'")).isEqualTo(3);
		assertThat(count(workspace, "parent_id is not null")).isEqualTo(10);
		assertThat(count(workspace, "archived")).isZero();

		Set<String> expenseRoots = owner.client()
				.sql("select name from categories where workspace_id = :w and parent_id is null and kind = 'EXPENSE'")
				.param("w", workspace).query(String.class).list().stream().collect(Collectors.toSet());
		Set<String> incomeRoots = owner.client()
				.sql("select name from categories where workspace_id = :w and parent_id is null and kind = 'INCOME'")
				.param("w", workspace).query(String.class).list().stream().collect(Collectors.toSet());
		assertThat(expenseRoots).isEqualTo(EXPENSE_ROOTS);
		assertThat(incomeRoots).isEqualTo(INCOME_ROOTS);

		List<String> mercado = owner.client().sql("""
				select c.name from categories c join categories p on p.id = c.parent_id
				where c.workspace_id = :w and p.name = 'Alimentação' order by c.name""")
				.param("w", workspace).query(String.class).list();
		assertThat(mercado).containsExactly("Mercado", "Restaurantes");
		long kindMismatch = owner.client().sql("""
				select count(*) from categories c join categories p on p.id = c.parent_id
				where c.workspace_id = :w and c.kind <> p.kind""")
				.param("w", workspace).query(Long.class).single();
		assertThat(kindMismatch).isZero();
	}

	@Test
	void eachWorkspaceHasItsOwnIndependentSet() throws Exception {
		UUID first = registerAndGetWorkspace();
		UUID second = registerAndGetWorkspace();

		owner.client().sql("update categories set archived = true where workspace_id = :w and name = 'Lazer'")
				.param("w", first).update();

		assertThat(count(first, "archived")).isEqualTo(1);
		assertThat(count(second, "archived")).isZero();
		assertThat(count(second, "true")).isEqualTo(20);
	}

	@Test
	void theSetIsNotRecreatedAfterThePersonArchivesOrEdits() throws Exception {
		String email = newEmail();
		MvcResult registered = register(mvc, email);
		UUID workspace = UUID
				.fromString(JsonPath.read(registered.getResponse().getContentAsString(), "$.workspaces[0].id"));
		owner.client().sql("update categories set archived = true where workspace_id = :w and name = 'Educação'")
				.param("w", workspace).update();
		owner.client().sql("update categories set name = 'Gastos diversos' where workspace_id = :w and name = 'Outros' and kind = 'EXPENSE'")
				.param("w", workspace).update();

		AuthSupport.login(mvc, email, PASSWORD);

		assertThat(count(workspace, "true")).isEqualTo(20);
		assertThat(count(workspace, "name = 'Educação' and not archived")).isZero();
		assertThat(count(workspace, "name = 'Outros'")).isEqualTo(1);
	}

	@Test
	void registeringTheSameEmailAgainCreatesNothing() throws Exception {
		String email = newEmail();
		register(mvc, email);
		long before = owner.client().sql("select count(*) from categories").query(Long.class).single();
		long workspacesBefore = owner.client().sql("select count(*) from workspaces").query(Long.class).single();

		MvcResult again = register(mvc, email);

		assertThat(again.getResponse().getStatus()).isEqualTo(409);
		assertThat(owner.client().sql("select count(*) from categories").query(Long.class).single()).isEqualTo(before);
		assertThat(owner.client().sql("select count(*) from workspaces").query(Long.class).single())
				.isEqualTo(workspacesBefore);
	}

	@Test
	void theApplicationRoleCannotRunTheSeedFunction() {
		assertThatThrownBy(() -> appJdbc.sql("select seed_default_categories(:w)").param("w", UUID.randomUUID())
				.query().singleRow())
				.hasStackTraceContaining("permission denied for function seed_default_categories");
	}

	@Test
	void simultaneousRegistrationsEachGetTheirOwnCompleteSet() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<UUID>> jobs = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				jobs.add(this::registerAndGetWorkspace);
			}
			List<UUID> workspaces = new ArrayList<>();
			for (Future<UUID> f : pool.invokeAll(jobs)) {
				workspaces.add(f.get());
			}

			assertThat(workspaces).doesNotHaveDuplicates().hasSize(4);
			for (UUID workspace : workspaces) {
				assertThat(count(workspace, "true")).isEqualTo(20);
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

}
