package com.tostao.tostao_api.category;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UpdateCategoryTest {

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

	private MvcResult update(Fixture who, UUID workspaceId, UUID id, String json) throws Exception {
		return mvc.perform(put(base(workspaceId) + "/" + id).cookie(session(who.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
	}

	private MvcResult update(Fixture fx, UUID id, String json) throws Exception {
		return update(fx, fx.workspaceId(), id, json);
	}

	private void assertInvalid(Fixture fx, UUID id, String json, String field) throws Exception {
		MvcResult result = update(fx, id, json);
		assertThat(result.getResponse().getStatus()).as(json).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).as(json).contains("\"field\":\"" + field + "\"");
	}

	private String nameInDb(UUID id) {
		return owner.client().sql("select name from categories where id = :id").param("id", id)
				.query(String.class).single();
	}

	@Test
	void changesNameColorAndIconAndTheListShowsThem() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID id = fixtures().insertCategory(fx.workspaceId(), null, "Antigo", "EXPENSE", false);

		MvcResult result = update(fx, id, "{\"name\":\"Novo\",\"color\":\"#1A9E5C\",\"icon\":\"paw\"}");

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		mvc.perform(get(base(fx.workspaceId())).cookie(session(fx.token())))
				.andExpect(jsonPath("$[0].name").value("Novo"))
				.andExpect(jsonPath("$[0].color").value("#1A9E5C"))
				.andExpect(jsonPath("$[0].icon").value("paw"))
				.andExpect(jsonPath("$[0].kind").value("EXPENSE"));
	}

	@Test
	void anAbsentColorAndIconClearTheValues() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID id = fixtures().insertCategory(fx.workspaceId(), null, "Colorida", "EXPENSE", false);
		update(fx, id, "{\"name\":\"Colorida\",\"color\":\"#1A9E5C\",\"icon\":\"paw\"}");

		update(fx, id, "{\"name\":\"Colorida\"}");

		mvc.perform(get(base(fx.workspaceId()) + "/" + id).cookie(session(fx.token())))
				.andExpect(jsonPath("$.color").doesNotExist())
				.andExpect(jsonPath("$.icon").doesNotExist());
	}

	@Test
	void kindAndParentMayBeEchoedButNeverChanged() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID root = fixtures().insertCategory(fx.workspaceId(), null, "Raiz", "EXPENSE", false);
		UUID otherRoot = fixtures().insertCategory(fx.workspaceId(), null, "Outra", "EXPENSE", false);
		UUID sub = fixtures().insertCategory(fx.workspaceId(), root, "Filha", "EXPENSE", false);

		assertThat(update(fx, sub, "{\"name\":\"Filha 2\",\"kind\":\"EXPENSE\",\"parentId\":\"%s\"}".formatted(root))
				.getResponse().getStatus()).isEqualTo(200);
		assertInvalid(fx, root, "{\"name\":\"Raiz\",\"kind\":\"INCOME\"}", "kind");
		assertInvalid(fx, sub, "{\"name\":\"Filha 2\",\"parentId\":\"%s\"}".formatted(otherRoot), "parentId");
		assertThat(nameInDb(sub)).isEqualTo("Filha 2");
		assertThat(owner.client().sql("select parent_id from categories where id = :id").param("id", sub)
				.query(UUID.class).single()).isEqualTo(root);
	}

	@Test
	void theSameValidationsAsCreationApply() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID id = fixtures().insertCategory(fx.workspaceId(), null, "Original", "EXPENSE", false);

		assertInvalid(fx, id, "{\"name\":\"\"}", "name");
		assertInvalid(fx, id, "{\"name\":\"%s\"}".formatted("x".repeat(61)), "name");
		assertInvalid(fx, id, "{\"name\":\"Ok\",\"color\":\"vermelho\"}", "color");
		assertInvalid(fx, id, "{\"name\":\"Ok\",\"icon\":\"\"}", "icon");
		assertInvalid(fx, id, "{\"name\":\"Ok\",\"icon\":\"qualquercoisa\"}", "icon");
		assertInvalid(fx, id, "{\"name\":\"Ok\",\"icon\":\"%s\"}".formatted("i".repeat(41)), "icon");
		for (String extra : new String[] { "\"archived\":true", "\"workspaceId\":\"" + UUID.randomUUID() + "\"" }) {
			assertThat(update(fx, id, "{\"name\":\"Ok\"," + extra + "}").getResponse().getStatus()).isEqualTo(400);
		}
		assertThat(nameInDb(id)).isEqualTo("Original");
	}

	@Test
	void aNameUsedByAnActiveSiblingIsRefusedButKeepingYourOwnIsFine() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID food = fixtures().insertCategory(fx.workspaceId(), null, "Comida", "EXPENSE", false);
		UUID home = fixtures().insertCategory(fx.workspaceId(), null, "Casa", "EXPENSE", false);
		UUID market = fixtures().insertCategory(fx.workspaceId(), food, "Mercado", "EXPENSE", false);
		UUID rent = fixtures().insertCategory(fx.workspaceId(), food, "Aluguel", "EXPENSE", false);
		UUID otherMarket = fixtures().insertCategory(fx.workspaceId(), home, "Feira", "EXPENSE", false);
		UUID income = fixtures().insertCategory(fx.workspaceId(), null, "Casa", "INCOME", false);

		assertThat(update(fx, rent, "{\"name\":\"mercado\"}").getResponse().getStatus()).isEqualTo(409);
		assertThat(nameInDb(rent)).isEqualTo("Aluguel");
		assertThat(update(fx, market, "{\"name\":\"Mercado\",\"color\":\"#000000\"}").getResponse().getStatus())
				.isEqualTo(200);
		assertThat(update(fx, otherMarket, "{\"name\":\"Mercado\"}").getResponse().getStatus()).isEqualTo(200);
		assertThat(update(fx, home, "{\"name\":\"COMIDA\"}").getResponse().getStatus()).isEqualTo(409);
		assertThat(update(fx, income, "{\"name\":\"Comida\"}").getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void anArchivedCategoryCannotBeEdited() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID id = fixtures().insertCategory(fx.workspaceId(), null, "Velha", "EXPENSE", true);

		MvcResult result = update(fx, id, "{\"name\":\"Nova\"}");

		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(nameInDb(id)).isEqualTo("Velha");
	}

	@Test
	void anotherWorkspacesCategoryOrAMissingOneIsNotFound() throws Exception {
		Fixture mine = fixtures().workspaceWithOwner();
		Fixture other = fixtures().workspaceWithOwner();
		UUID foreign = fixtures().insertCategory(other.workspaceId(), null, "Alheia", "EXPENSE", false);

		assertThat(update(mine, foreign, "{\"name\":\"Invadida\"}").getResponse().getStatus()).isEqualTo(404);
		assertThat(update(mine, UUID.randomUUID(), "{\"name\":\"Nada\"}").getResponse().getStatus()).isEqualTo(404);
		assertThat(nameInDb(foreign)).isEqualTo("Alheia");
	}

	@Test
	void rolesAndMembershipDecideWhoCanEdit() throws Exception {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture viewer = fixtures().addMember(ownerFx.workspaceId(), "VIEWER");
		Fixture editor = fixtures().addMember(ownerFx.workspaceId(), "EDITOR");
		Fixture stranger = fixtures().workspaceWithOwner();
		UUID id = fixtures().insertCategory(ownerFx.workspaceId(), null, "Original", "EXPENSE", false);

		assertThat(update(viewer, ownerFx.workspaceId(), id, "{\"name\":\"Viewer\"}").getResponse().getStatus())
				.isEqualTo(403);
		MvcResult asStranger = update(stranger, ownerFx.workspaceId(), id, "{\"name\":\"Intruso\"}");
		MvcResult missing = update(stranger, UUID.randomUUID(), id, "{\"name\":\"Intruso\"}");
		assertThat(asStranger.getResponse().getStatus()).isEqualTo(404);
		assertThat(asStranger.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", ""))
				.isEqualTo(missing.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", ""));
		assertThat(nameInDb(id)).isEqualTo("Original");

		assertThat(update(editor, ownerFx.workspaceId(), id, "{\"name\":\"Editor\"}").getResponse().getStatus())
				.isEqualTo(200);
		assertThat(update(ownerFx, ownerFx.workspaceId(), id, "{\"name\":\"Dono\"}").getResponse().getStatus())
				.isEqualTo(200);
		mvc.perform(put(base(ownerFx.workspaceId()) + "/" + id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"Anonimo\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void simultaneousRenamesToTheSameNameNeverCauseAnInternalError() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			ids.add(fixtures().insertCategory(fx.workspaceId(), null, "Origem " + i, "EXPENSE", false));
		}
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<Integer>> jobs = new ArrayList<>();
			for (UUID id : ids) {
				jobs.add(() -> update(fx, id, "{\"name\":\"Alvo\"}").getResponse().getStatus());
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(jobs)) {
				statuses.add(f.get());
			}

			assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
			assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(3);
		}
		finally {
			pool.shutdownNow();
		}
	}

}
