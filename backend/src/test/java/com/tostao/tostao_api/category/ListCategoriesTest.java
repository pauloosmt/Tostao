package com.tostao.tostao_api.category;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ListCategoriesTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private String base(UUID workspaceId) {
		return "/api/v1/workspaces/" + workspaceId + "/categories";
	}

	private Fixture withDefaults() {
		Fixture fx = fixtures().workspaceWithOwner();
		fixtures().seedDefaults(fx.workspaceId());
		return fx;
	}

	@Test
	void listsTheDefaultSetAsATreeOfTwoLevels() throws Exception {
		Fixture fx = withDefaults();

		mvc.perform(get(base(fx.workspaceId())).cookie(session(fx.token())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(10)))
				.andExpect(jsonPath("$[?(@.name == 'Alimentação')].subcategories[*].name",
						contains("Mercado", "Restaurantes")))
				.andExpect(jsonPath("$[?(@.name == 'Educação')].subcategories[*]", hasSize(0)))
				.andExpect(jsonPath("$[?(@.name == 'Educação')].subcategories", contains(hasSize(0))))
				.andExpect(jsonPath("$[*].parentId", containsInAnyOrder(nullValue(), nullValue(), nullValue(),
						nullValue(), nullValue(), nullValue(), nullValue(), nullValue(), nullValue(), nullValue())));
	}

	@Test
	void ordersEachLevelByNameIgnoringCase() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		fixtures().insertCategory(fx.workspaceId(), null, "banana", "EXPENSE", false);
		fixtures().insertCategory(fx.workspaceId(), null, "Zebra", "EXPENSE", false);
		UUID apple = fixtures().insertCategory(fx.workspaceId(), null, "Apple", "EXPENSE", false);
		fixtures().insertCategory(fx.workspaceId(), null, "cherry", "EXPENSE", false);
		fixtures().insertCategory(fx.workspaceId(), apple, "beta", "EXPENSE", false);
		fixtures().insertCategory(fx.workspaceId(), apple, "Alpha", "EXPENSE", false);
		fixtures().insertCategory(fx.workspaceId(), apple, "gamma", "EXPENSE", false);

		mvc.perform(get(base(fx.workspaceId())).cookie(session(fx.token())))
				.andExpect(jsonPath("$[*].name", contains("Apple", "banana", "cherry", "Zebra")))
				.andExpect(jsonPath("$[0].subcategories[*].name", contains("Alpha", "beta", "gamma")));
	}

	@Test
	void filtersByKind() throws Exception {
		Fixture fx = withDefaults();

		mvc.perform(get(base(fx.workspaceId()) + "?kind=INCOME").cookie(session(fx.token())))
				.andExpect(jsonPath("$", hasSize(3)))
				.andExpect(jsonPath("$[*].name", containsInAnyOrder("Salário", "Rendimentos", "Outros")))
				.andExpect(jsonPath("$[*].kind", containsInAnyOrder("INCOME", "INCOME", "INCOME")));
		mvc.perform(get(base(fx.workspaceId()) + "?kind=EXPENSE").cookie(session(fx.token())))
				.andExpect(jsonPath("$", hasSize(7)));
	}

	@Test
	void anUnknownKindIsRejectedWithTheFieldName() throws Exception {
		Fixture fx = withDefaults();

		mvc.perform(get(base(fx.workspaceId()) + "?kind=XYZ").cookie(session(fx.token())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("kind"));
	}

	@Test
	void archivedCategoriesAreHiddenUnlessAsked() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID active = fixtures().insertCategory(fx.workspaceId(), null, "Ativa", "EXPENSE", false);
		fixtures().insertCategory(fx.workspaceId(), null, "Velha", "EXPENSE", true);
		fixtures().insertCategory(fx.workspaceId(), active, "Filha ativa", "EXPENSE", false);
		fixtures().insertCategory(fx.workspaceId(), active, "Filha velha", "EXPENSE", true);

		mvc.perform(get(base(fx.workspaceId())).cookie(session(fx.token())))
				.andExpect(jsonPath("$[*].name", contains("Ativa")))
				.andExpect(jsonPath("$[0].subcategories[*].name", contains("Filha ativa")));
		mvc.perform(get(base(fx.workspaceId()) + "?includeArchived=true").cookie(session(fx.token())))
				.andExpect(jsonPath("$[*].name", contains("Ativa", "Velha")))
				.andExpect(jsonPath("$[1].archived").value(true))
				.andExpect(jsonPath("$[0].subcategories[*].name", contains("Filha ativa", "Filha velha")))
				.andExpect(jsonPath("$[0].subcategories[1].archived").value(true));
	}

	@Test
	void getsOneCategoryWithItsParentAndKind() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID root = fixtures().insertCategory(fx.workspaceId(), null, "Casa", "EXPENSE", false);
		UUID child = fixtures().insertCategory(fx.workspaceId(), root, "Luz", "EXPENSE", false);

		mvc.perform(get(base(fx.workspaceId()) + "/" + child).cookie(session(fx.token())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(child.toString()))
				.andExpect(jsonPath("$.parentId").value(root.toString()))
				.andExpect(jsonPath("$.kind").value("EXPENSE"))
				.andExpect(jsonPath("$.archived").value(false))
				.andExpect(jsonPath("$.createdAt").exists())
				.andExpect(jsonPath("$.workspaceId").doesNotExist());
		mvc.perform(get(base(fx.workspaceId()) + "/" + root).cookie(session(fx.token())))
				.andExpect(jsonPath("$.parentId").value(nullValue()));
	}

	@Test
	void aCategoryOfAnotherWorkspaceOrAMalformedIdIsNotFound() throws Exception {
		Fixture mine = fixtures().workspaceWithOwner();
		Fixture other = fixtures().workspaceWithOwner();
		UUID foreign = fixtures().insertCategory(other.workspaceId(), null, "Alheia", "EXPENSE", false);

		mvc.perform(get(base(mine.workspaceId()) + "/" + foreign).cookie(session(mine.token())))
				.andExpect(status().isNotFound());
		mvc.perform(get(base(mine.workspaceId()) + "/" + UUID.randomUUID()).cookie(session(mine.token())))
				.andExpect(status().isNotFound());
		mvc.perform(get(base(mine.workspaceId()) + "/abc").cookie(session(mine.token())))
				.andExpect(status().isNotFound());
	}

	@Test
	void aWorkspaceWithoutCategoriesReturnsAnEmptyList() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		String body = mvc.perform(get(base(fx.workspaceId())).cookie(session(fx.token())))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

		assertThat(body).isEqualTo("[]");
	}

}
