package com.tostao.tostao_api.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * As invariantes da V4 valem no banco, mesmo para quem escreve direto (aqui, o dono do schema): hierarquia de
 * dois níveis, tipo herdado, formato dos campos e nome único entre irmãs ativas.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CategoryHierarchyDbTest {

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private void insert(UUID workspace, UUID parent, String name, String kind, String color, String icon) {
		owner.client().sql("""
				insert into categories (workspace_id, parent_id, name, kind, color, icon)
				values (:w, :p, :n, :k, :c, :i)""")
				.param("w", workspace).param("p", parent).param("n", name).param("k", kind)
				.param("c", color).param("i", icon).update();
	}

	@Test
	void aSubcategoryMustHaveTheKindOfItsParent() {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID parent = fixtures().insertCategory(fx.workspaceId(), null, "Despesa", "EXPENSE", false);

		assertThatThrownBy(() -> insert(fx.workspaceId(), parent, "Filha de receita", "INCOME", null, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		insert(fx.workspaceId(), parent, "Filha de despesa", "EXPENSE", null, null);
	}

	@Test
	void thereIsNoThirdLevel() {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID root = fixtures().insertCategory(fx.workspaceId(), null, "Raiz", "EXPENSE", false);
		UUID child = fixtures().insertCategory(fx.workspaceId(), root, "Filha", "EXPENSE", false);

		assertThatThrownBy(() -> insert(fx.workspaceId(), child, "Neta", "EXPENSE", null, null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uma subcategoria não pode ter filhas");
	}

	@Test
	void aCategoryWithChildrenCannotGainAParentNorChangeKind() {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID other = fixtures().insertCategory(fx.workspaceId(), null, "Outra raiz", "EXPENSE", false);
		UUID root = fixtures().insertCategory(fx.workspaceId(), null, "Raiz", "EXPENSE", false);
		fixtures().insertCategory(fx.workspaceId(), root, "Filha", "EXPENSE", false);

		assertThatThrownBy(() -> owner.client().sql("update categories set parent_id = :p where id = :id")
				.param("p", other).param("id", root).update())
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uma categoria com filhas não pode ter pai");
		assertThatThrownBy(() -> owner.client().sql("update categories set kind = 'INCOME' where id = :id")
				.param("id", root).update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void fieldFormatsAreEnforcedByTheDatabase() {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID w = fx.workspaceId();

		assertThatThrownBy(() -> insert(w, null, " espaço", "EXPENSE", null, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(w, null, "x".repeat(61), "EXPENSE", null, null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(w, null, "Cor ruim", "EXPENSE", "vermelho", null))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(w, null, "Ícone longo", "EXPENSE", null, "i".repeat(41)))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> insert(w, null, "Ícone vazio", "EXPENSE", null, ""))
				.isInstanceOf(DataIntegrityViolationException.class);
		insert(w, null, "x".repeat(60), "EXPENSE", "#1A9E5C", "i".repeat(40));
	}

	@Test
	void activeSiblingsCannotShareANameIgnoringCase() {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID food = fixtures().insertCategory(w, null, "Alimentação", "EXPENSE", false);
		UUID home = fixtures().insertCategory(w, null, "Moradia", "EXPENSE", false);
		fixtures().insertCategory(w, food, "Mercado", "EXPENSE", false);

		assertThatThrownBy(() -> insert(w, food, "mercado", "EXPENSE", null, null))
				.isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining("categories_active_child_name_key");
		assertThatThrownBy(() -> insert(w, null, "ALIMENTAÇÃO", "EXPENSE", null, null))
				.isInstanceOf(DuplicateKeyException.class)
				.hasMessageContaining("categories_active_root_name_key");

		// o mesmo nome sob outro pai, ou em outro tipo, é aceito
		insert(w, home, "Mercado", "EXPENSE", null, null);
		insert(w, null, "Alimentação", "INCOME", null, null);
	}

	@Test
	void anArchivedCategoryDoesNotBlockTheName() {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID food = fixtures().insertCategory(w, null, "Alimentação", "EXPENSE", false);
		fixtures().insertCategory(w, food, "Mercado", "EXPENSE", true);
		fixtures().insertCategory(w, null, "Lazer", "EXPENSE", true);

		insert(w, food, "Mercado", "EXPENSE", null, null);
		insert(w, null, "Lazer", "EXPENSE", null, null);
		assertThat(owner.client().sql("select count(*) from categories where workspace_id = :w and lower(name) = 'lazer'")
				.param("w", w).query(Long.class).single()).isEqualTo(2);
	}

}
