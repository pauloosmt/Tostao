package com.tostao.tostao_api.category;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CreateCategoryTest {

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

	private MvcResult create(Fixture fx, String json) throws Exception {
		return mvc.perform(post(base(fx.workspaceId())).cookie(session(fx.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
	}

	private static String root(String name, String kind) {
		return "{\"name\":\"%s\",\"kind\":\"%s\"}".formatted(name, kind);
	}

	private static String child(String name, UUID parent) {
		return "{\"name\":\"%s\",\"parentId\":\"%s\"}".formatted(name, parent);
	}

	private void assertInvalid(Fixture fx, String json, String field) throws Exception {
		MvcResult result = create(fx, json);
		assertThat(result.getResponse().getStatus()).as(json).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).as(json).contains("\"field\":\"" + field + "\"");
	}

	private long count(UUID workspaceId) {
		return owner.client().sql("select count(*) from categories where workspace_id = :w").param("w", workspaceId)
				.query(Long.class).single();
	}

	@Test
	void createsARootCategoryAndItAppearsInTheList() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		MvcResult result = create(fx, "{\"name\":\"Pets\",\"kind\":\"EXPENSE\",\"color\":\"#1A9E5C\",\"icon\":\"paw\"}");

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		assertThat(result.getResponse().getHeader("Location")).isEqualTo(base(fx.workspaceId()) + "/" + id);
		mvc.perform(get(base(fx.workspaceId())).cookie(session(fx.token())))
				.andExpect(jsonPath("$[0].name").value("Pets"))
				.andExpect(jsonPath("$[0].kind").value("EXPENSE"))
				.andExpect(jsonPath("$[0].color").value("#1A9E5C"))
				.andExpect(jsonPath("$[0].icon").value("paw"))
				.andExpect(jsonPath("$[0].parentId").doesNotExist())
				.andExpect(jsonPath("$[0].archived").value(false))
				.andExpect(jsonPath("$[0].createdAt").exists());
		assertThat(result.getResponse().getContentAsString()).doesNotContain("workspace");
	}

	@Test
	void createsASubcategoryThatInheritsTheKind() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID pets = fixtures().insertCategory(fx.workspaceId(), null, "Pets", "INCOME", false);

		MvcResult result = create(fx, child("Racao", pets));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.kind")).isEqualTo("INCOME");
		assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.parentId"))
				.isEqualTo(pets.toString());
		mvc.perform(get(base(fx.workspaceId())).cookie(session(fx.token())))
				.andExpect(jsonPath("$[0].subcategories[0].name").value("Racao"));
	}

	@Test
	void aSubcategoryAcceptsTheParentsKindButNotAnother() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID pets = fixtures().insertCategory(fx.workspaceId(), null, "Pets", "EXPENSE", false);

		assertThat(create(fx, "{\"name\":\"Igual\",\"kind\":\"EXPENSE\",\"parentId\":\"%s\"}".formatted(pets))
				.getResponse().getStatus()).isEqualTo(201);
		assertInvalid(fx, "{\"name\":\"Diferente\",\"kind\":\"INCOME\",\"parentId\":\"%s\"}".formatted(pets), "kind");
		assertThat(count(fx.workspaceId())).isEqualTo(2);
	}

	@Test
	void aRootCategoryNeedsAKind() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		assertInvalid(fx, "{\"name\":\"Sem tipo\"}", "kind");
		assertThat(count(fx.workspaceId())).isZero();
	}

	@Test
	void thereAreOnlyTwoLevels() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID root = fixtures().insertCategory(fx.workspaceId(), null, "Raiz", "EXPENSE", false);
		UUID sub = fixtures().insertCategory(fx.workspaceId(), root, "Filha", "EXPENSE", false);

		assertInvalid(fx, child("Neta", sub), "parentId");
		assertThat(count(fx.workspaceId())).isEqualTo(2);
	}

	@Test
	void theParentMustExistInThisWorkspaceAndBeActive() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		Fixture other = fixtures().workspaceWithOwner();
		UUID foreign = fixtures().insertCategory(other.workspaceId(), null, "Alheia", "EXPENSE", false);
		UUID archived = fixtures().insertCategory(fx.workspaceId(), null, "Velha", "EXPENSE", true);

		assertThat(create(fx, child("Filha", foreign)).getResponse().getStatus()).isEqualTo(404);
		assertThat(create(fx, child("Filha", UUID.randomUUID())).getResponse().getStatus()).isEqualTo(404);
		assertThat(create(fx, child("Filha", archived)).getResponse().getStatus()).isEqualTo(409);
		assertThat(count(fx.workspaceId())).isEqualTo(1);
		assertThat(count(other.workspaceId())).isEqualTo(1);
	}

	@Test
	void invalidFieldsAreRejectedWithTheFieldName() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		assertInvalid(fx, root("", "EXPENSE"), "name");
		assertInvalid(fx, root("   ", "EXPENSE"), "name");
		assertInvalid(fx, root("x".repeat(61), "EXPENSE"), "name");
		assertInvalid(fx, "{\"name\":\"Cor\",\"kind\":\"EXPENSE\",\"color\":\"vermelho\"}", "color");
		assertInvalid(fx, "{\"name\":\"Cor\",\"kind\":\"EXPENSE\",\"color\":\"#12345\"}", "color");
		assertInvalid(fx, "{\"name\":\"Ico\",\"kind\":\"EXPENSE\",\"icon\":\"\"}", "icon");
		assertInvalid(fx, "{\"name\":\"Ico\",\"kind\":\"EXPENSE\",\"icon\":\"%s\"}".formatted("i".repeat(41)), "icon");
		assertThat(create(fx, root("Tipo", "OUTRO")).getResponse().getStatus()).isEqualTo(400);
		assertThat(count(fx.workspaceId())).isZero();
	}

	@Test
	void fieldsOutsideTheAllowedListAreRejected() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID other = UUID.randomUUID();

		for (String extra : new String[] { "\"workspaceId\":\"" + other + "\"", "\"archived\":true",
				"\"createdAt\":\"2020-01-01T00:00:00Z\"" }) {
			String json = "{\"name\":\"Extra\",\"kind\":\"EXPENSE\"," + extra + "}";
			assertThat(create(fx, json).getResponse().getStatus()).as(json).isEqualTo(400);
		}
		assertThat(count(fx.workspaceId())).isZero();
	}

	@Test
	void theNameIsStoredWithoutSurroundingSpaces() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		MvcResult result = create(fx, root("  Pets  ", "EXPENSE"));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.name")).isEqualTo("Pets");
	}

	@Test
	void theSameNameAmongActiveSiblingsIsRefusedIgnoringCaseAndSpaces() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID food = fixtures().insertCategory(fx.workspaceId(), null, "Comida", "EXPENSE", false);
		UUID home = fixtures().insertCategory(fx.workspaceId(), null, "Casa", "EXPENSE", false);

		assertThat(create(fx, child("Mercado", food)).getResponse().getStatus()).isEqualTo(201);
		assertThat(create(fx, child("mercado ", food)).getResponse().getStatus()).isEqualTo(409);
		assertThat(create(fx, child("Mercado", home)).getResponse().getStatus()).isEqualTo(201);
		assertThat(create(fx, root("comida", "EXPENSE")).getResponse().getStatus()).isEqualTo(409);
		assertThat(create(fx, root("Comida", "INCOME")).getResponse().getStatus()).isEqualTo(201);
	}

	@Test
	void theSameNameIsAllowedAgainOnceTheOtherIsArchived() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		fixtures().insertCategory(fx.workspaceId(), null, "Outros", "EXPENSE", false);

		assertThat(create(fx, root("outros", "EXPENSE")).getResponse().getStatus()).isEqualTo(409);
		owner.client().sql("update categories set archived = true where workspace_id = :w")
				.param("w", fx.workspaceId()).update();

		assertThat(create(fx, root("Outros", "EXPENSE")).getResponse().getStatus()).isEqualTo(201);
	}

	@Test
	void theDefaultSetAllowsOutrosInBothKindsButNotAnotherOne() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		fixtures().seedDefaults(fx.workspaceId());

		assertThat(create(fx, root("outros", "EXPENSE")).getResponse().getStatus()).isEqualTo(409);
		assertThat(create(fx, root("outros", "INCOME")).getResponse().getStatus()).isEqualTo(409);
		mvc.perform(get(base(fx.workspaceId()) + "?kind=INCOME").cookie(session(fx.token())))
				.andExpect(status().isOk());
	}

}
