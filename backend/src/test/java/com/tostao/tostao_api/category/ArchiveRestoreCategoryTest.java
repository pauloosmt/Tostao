package com.tostao.tostao_api.category;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
class ArchiveRestoreCategoryTest {

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

	private MvcResult archive(Fixture who, UUID workspaceId, UUID id) throws Exception {
		return mvc.perform(post(base(workspaceId) + "/" + id + "/archive").cookie(session(who.token()))).andReturn();
	}

	private MvcResult restore(Fixture who, UUID workspaceId, UUID id) throws Exception {
		return mvc.perform(post(base(workspaceId) + "/" + id + "/restore").cookie(session(who.token()))).andReturn();
	}

	private boolean archivedInDb(UUID id) {
		return owner.client().sql("select archived from categories where id = :id").param("id", id)
				.query(Boolean.class).single();
	}

	private long activeUnderArchivedParents(UUID workspaceId) {
		return owner.client().sql("""
				select count(*) from categories c join categories p on p.id = c.parent_id
				where c.workspace_id = :w and not c.archived and p.archived""")
				.param("w", workspaceId).query(Long.class).single();
	}

	@Test
	void archivingARootArchivesItsActiveSubcategoriesAndKeepsEverything() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID root = fixtures().insertCategory(fx.workspaceId(), null, "Casa", "EXPENSE", false);
		UUID rent = fixtures().insertCategory(fx.workspaceId(), root, "Aluguel", "EXPENSE", false);
		UUID light = fixtures().insertCategory(fx.workspaceId(), root, "Luz", "EXPENSE", false);
		UUID old = fixtures().insertCategory(fx.workspaceId(), root, "Antiga", "EXPENSE", true);
		fixtures().insertCategory(fx.workspaceId(), null, "Outra", "EXPENSE", false);
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Conta", "CHECKING", 0, false);
		UUID transaction = fixtures().insertTransaction(fx.workspaceId(), account, fx.userId(), -5_000, false);
		owner.client().sql("update transactions set category_id = :c where id = :t").param("c", rent)
				.param("t", transaction).update();

		MvcResult result = archive(fx, fx.workspaceId(), root);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString()).contains("\"archived\":true");
		mvc.perform(get(base(fx.workspaceId())).cookie(session(fx.token())))
				.andExpect(jsonPath("$[*].name", contains("Outra")));
		mvc.perform(get(base(fx.workspaceId()) + "?includeArchived=true").cookie(session(fx.token())))
				.andExpect(jsonPath("$[*].name", contains("Casa", "Outra")))
				.andExpect(jsonPath("$[0].archived").value(true))
				.andExpect(jsonPath("$[0].subcategories[*].archived", contains(true, true, true)));
		assertThat(archivedInDb(rent)).isTrue();
		assertThat(archivedInDb(light)).isTrue();
		assertThat(archivedInDb(old)).isTrue();
		assertThat(owner.client().sql("select category_id from transactions where id = :t").param("t", transaction)
				.query(UUID.class).single()).isEqualTo(rent);
		assertThat(activeUnderArchivedParents(fx.workspaceId())).isZero();
	}

	@Test
	void archivingASubcategoryArchivesOnlyIt() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID root = fixtures().insertCategory(fx.workspaceId(), null, "Casa", "EXPENSE", false);
		UUID rent = fixtures().insertCategory(fx.workspaceId(), root, "Aluguel", "EXPENSE", false);
		UUID light = fixtures().insertCategory(fx.workspaceId(), root, "Luz", "EXPENSE", false);

		assertThat(archive(fx, fx.workspaceId(), rent).getResponse().getStatus()).isEqualTo(200);

		assertThat(archivedInDb(rent)).isTrue();
		assertThat(archivedInDb(light)).isFalse();
		assertThat(archivedInDb(root)).isFalse();
	}

	@Test
	void archivingAgainChangesNothing() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID root = fixtures().insertCategory(fx.workspaceId(), null, "Casa", "EXPENSE", false);
		UUID child = fixtures().insertCategory(fx.workspaceId(), root, "Luz", "EXPENSE", false);
		String first = archive(fx, fx.workspaceId(), root).getResponse().getContentAsString();
		// a pessoa restaura só a principal; arquivar a principal outra vez a arquiva de novo e a filha já estava
		restore(fx, fx.workspaceId(), root);
		archive(fx, fx.workspaceId(), root);
		MvcResult again = archive(fx, fx.workspaceId(), root);

		assertThat(again.getResponse().getStatus()).isEqualTo(200);
		assertThat(again.getResponse().getContentAsString()).isEqualTo(first);
		assertThat(archivedInDb(child)).isTrue();
	}

	@Test
	void restoringARootDoesNotRestoreItsSubcategories() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID root = fixtures().insertCategory(fx.workspaceId(), null, "Casa", "EXPENSE", false);
		UUID child = fixtures().insertCategory(fx.workspaceId(), root, "Luz", "EXPENSE", false);
		archive(fx, fx.workspaceId(), root);

		MvcResult result = restore(fx, fx.workspaceId(), root);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString()).contains("\"archived\":false");
		assertThat(archivedInDb(root)).isFalse();
		assertThat(archivedInDb(child)).isTrue();
		mvc.perform(get(base(fx.workspaceId())).cookie(session(fx.token())))
				.andExpect(jsonPath("$[0].subcategories.length()").value(0));
	}

	@Test
	void aSubcategoryOnlyComesBackAfterItsRootIsActive() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID root = fixtures().insertCategory(fx.workspaceId(), null, "Casa", "EXPENSE", false);
		UUID child = fixtures().insertCategory(fx.workspaceId(), root, "Luz", "EXPENSE", false);
		archive(fx, fx.workspaceId(), root);

		assertThat(restore(fx, fx.workspaceId(), child).getResponse().getStatus()).isEqualTo(409);
		assertThat(archivedInDb(child)).isTrue();

		assertThat(restore(fx, fx.workspaceId(), root).getResponse().getStatus()).isEqualTo(200);
		assertThat(restore(fx, fx.workspaceId(), child).getResponse().getStatus()).isEqualTo(200);
		assertThat(archivedInDb(child)).isFalse();
		assertThat(activeUnderArchivedParents(fx.workspaceId())).isZero();
	}

	@Test
	void restoringAnActiveCategoryDoesNothing() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID id = fixtures().insertCategory(fx.workspaceId(), null, "Ativa", "EXPENSE", false);
		String before = mvc.perform(get(base(fx.workspaceId()) + "/" + id).cookie(session(fx.token()))).andReturn()
				.getResponse().getContentAsString();

		MvcResult result = restore(fx, fx.workspaceId(), id);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString()).isEqualTo(before);
	}

	@Test
	void restoringIsRefusedWhenAnActiveSiblingUsesTheName() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID archivedRoot = fixtures().insertCategory(fx.workspaceId(), null, "Casa", "EXPENSE", true);
		fixtures().insertCategory(fx.workspaceId(), null, "casa", "EXPENSE", false);
		UUID parent = fixtures().insertCategory(fx.workspaceId(), null, "Pai", "EXPENSE", false);
		UUID archivedChild = fixtures().insertCategory(fx.workspaceId(), parent, "Luz", "EXPENSE", true);
		fixtures().insertCategory(fx.workspaceId(), parent, "luz", "EXPENSE", false);

		assertThat(restore(fx, fx.workspaceId(), archivedRoot).getResponse().getStatus()).isEqualTo(409);
		assertThat(restore(fx, fx.workspaceId(), archivedChild).getResponse().getStatus()).isEqualTo(409);
		assertThat(archivedInDb(archivedRoot)).isTrue();
		assertThat(archivedInDb(archivedChild)).isTrue();
	}

	@Test
	void thereIsNoWayToDeleteACategory() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID id = fixtures().insertCategory(fx.workspaceId(), null, "Permanente", "EXPENSE", false);

		MvcResult result = mvc.perform(delete(base(fx.workspaceId()) + "/" + id).cookie(session(fx.token())))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isIn(404, 405);
		assertThat(owner.client().sql("select count(*) from categories where id = :id").param("id", id)
				.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void permissionsAndIsolationApplyToArchiveAndRestore() throws Exception {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture viewer = fixtures().addMember(ownerFx.workspaceId(), "VIEWER");
		Fixture editor = fixtures().addMember(ownerFx.workspaceId(), "EDITOR");
		Fixture stranger = fixtures().workspaceWithOwner();
		UUID active = fixtures().insertCategory(ownerFx.workspaceId(), null, "Ativa", "EXPENSE", false);
		UUID archived = fixtures().insertCategory(ownerFx.workspaceId(), null, "Arquivada", "EXPENSE", true);
		UUID foreign = fixtures().insertCategory(stranger.workspaceId(), null, "Alheia", "EXPENSE", false);

		assertThat(archive(viewer, ownerFx.workspaceId(), active).getResponse().getStatus()).isEqualTo(403);
		assertThat(restore(viewer, ownerFx.workspaceId(), archived).getResponse().getStatus()).isEqualTo(403);
		assertThat(archivedInDb(active)).isFalse();
		assertThat(archivedInDb(archived)).isTrue();

		MvcResult asStranger = archive(stranger, ownerFx.workspaceId(), active);
		MvcResult missing = archive(stranger, UUID.randomUUID(), active);
		assertThat(asStranger.getResponse().getStatus()).isEqualTo(404);
		assertThat(asStranger.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", ""))
				.isEqualTo(missing.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", ""));
		assertThat(restore(stranger, ownerFx.workspaceId(), archived).getResponse().getStatus()).isEqualTo(404);
		assertThat(archive(ownerFx, ownerFx.workspaceId(), foreign).getResponse().getStatus()).isEqualTo(404);
		assertThat(restore(ownerFx, ownerFx.workspaceId(), foreign).getResponse().getStatus()).isEqualTo(404);
		assertThat(archive(ownerFx, ownerFx.workspaceId(), UUID.randomUUID()).getResponse().getStatus())
				.isEqualTo(404);
		assertThat(archivedInDb(foreign)).isFalse();

		assertThat(archive(editor, ownerFx.workspaceId(), active).getResponse().getStatus()).isEqualTo(200);
		assertThat(restore(ownerFx, ownerFx.workspaceId(), archived).getResponse().getStatus()).isEqualTo(200);
		mvc.perform(post(base(ownerFx.workspaceId()) + "/" + active + "/archive")).andExpect(status().isUnauthorized());
	}

}
