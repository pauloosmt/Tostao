package com.tostao.tostao_api.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.category.dto.CategoryResponse;
import com.tostao.tostao_api.category.dto.CategoryTreeResponse;
import com.tostao.tostao_api.common.error.CategoryNotFoundException;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * Camada de serviço, sozinha (SC-006): o serviço roda com a conexão do dono, que ignora o RLS. Mesmo
 * assim, o filtro explícito por {@code workspace_id} das consultas isola os workspaces.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CategoryServiceFilterTest {

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	@Test
	void theServiceFilterAloneIsolatesWorkspacesWithRlsBypassed() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		CategoryService service = new CategoryService(owner.client());
		Fixture a = fixtures.workspaceWithOwner();
		Fixture b = fixtures.workspaceWithOwner();
		UUID categoryA = fixtures.insertCategory(a.workspaceId(), null, "Categoria A", "EXPENSE", false);
		UUID categoryB = fixtures.insertCategory(b.workspaceId(), null, "Categoria B", "EXPENSE", false);
		fixtures.insertCategory(b.workspaceId(), categoryB, "Filha de B", "EXPENSE", false);
		fixtures.insertCategory(b.workspaceId(), null, "Arquivada B", "INCOME", true);

		assertThat(service.get(a.workspaceId(), categoryA).name()).isEqualTo("Categoria A");
		assertThat(service.get(b.workspaceId(), categoryB).name()).isEqualTo("Categoria B");
		assertThatThrownBy(() -> service.get(a.workspaceId(), categoryB)).isInstanceOf(CategoryNotFoundException.class);
		assertThatThrownBy(() -> service.get(b.workspaceId(), categoryA)).isInstanceOf(CategoryNotFoundException.class);
		assertThatThrownBy(() -> service.get(a.workspaceId(), UUID.randomUUID()))
				.isInstanceOf(CategoryNotFoundException.class);

		assertThat(service.list(a.workspaceId(), null, true)).extracting(CategoryTreeResponse::name)
				.containsExactly("Categoria A");
		CategoryTreeResponse treeB = service.list(b.workspaceId(), null, true).get(0);
		assertThat(service.list(b.workspaceId(), null, true)).extracting(CategoryTreeResponse::name)
				.containsExactly("Arquivada B", "Categoria B");
		assertThat(service.list(b.workspaceId(), null, true).get(1).subcategories())
				.extracting(CategoryResponse::name).containsExactly("Filha de B");
		assertThat(treeB.archived()).isTrue();
	}

}
