package com.tostao.tostao_api.category;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** Acesso às categorias por papel e por workspace: quem não é membro não descobre nada (FR-016, FR-017). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CategoryAccessTest {

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

	/** Corpo sem o campo {@code instance}, que repete a rota pedida e por isso muda de um caso para outro. */
	private static String withoutInstance(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", "");
	}

	@Test
	void aNonMemberGetsTheSameAnswerAsForAMissingWorkspace() throws Exception {
		Fixture member = fixtures().workspaceWithOwner();
		Fixture stranger = fixtures().workspaceWithOwner();
		UUID category = fixtures().insertCategory(member.workspaceId(), null, "Privada", "EXPENSE", false);
		UUID missingWorkspace = UUID.randomUUID();

		MvcResult list = mvc.perform(get(base(member.workspaceId())).cookie(session(stranger.token()))).andReturn();
		MvcResult listMissing = mvc.perform(get(base(missingWorkspace)).cookie(session(stranger.token()))).andReturn();
		MvcResult one = mvc.perform(get(base(member.workspaceId()) + "/" + category).cookie(session(stranger.token())))
				.andReturn();
		MvcResult oneMissing = mvc
				.perform(get(base(missingWorkspace) + "/" + category).cookie(session(stranger.token()))).andReturn();

		assertThat(list.getResponse().getStatus()).isEqualTo(404);
		assertThat(one.getResponse().getStatus()).isEqualTo(404);
		assertThat(withoutInstance(list)).isEqualTo(withoutInstance(listMissing));
		assertThat(withoutInstance(one)).isEqualTo(withoutInstance(oneMissing));
		assertThat(list.getResponse().getContentAsString()).doesNotContain("Privada");
	}

	@Test
	void viewerEditorAndOwnerCanRead() throws Exception {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture viewer = fixtures().addMember(ownerFx.workspaceId(), "VIEWER");
		Fixture editor = fixtures().addMember(ownerFx.workspaceId(), "EDITOR");
		UUID category = fixtures().insertCategory(ownerFx.workspaceId(), null, "Visivel", "EXPENSE", false);

		for (Fixture reader : new Fixture[] { ownerFx, viewer, editor }) {
			mvc.perform(get(base(ownerFx.workspaceId())).cookie(session(reader.token())))
					.andExpect(status().isOk());
			mvc.perform(get(base(ownerFx.workspaceId()) + "/" + category).cookie(session(reader.token())))
					.andExpect(status().isOk());
		}
	}

	@Test
	void aCategoryOfAnotherWorkspaceIsNotFoundThroughMyRoute() throws Exception {
		Fixture mine = fixtures().workspaceWithOwner();
		Fixture other = fixtures().workspaceWithOwner();
		UUID foreign = fixtures().insertCategory(other.workspaceId(), null, "Alheia", "EXPENSE", false);

		mvc.perform(get(base(mine.workspaceId()) + "/" + foreign).cookie(session(mine.token())))
				.andExpect(status().isNotFound());
	}

	@Test
	void withoutASessionTheAnswerIsUnauthorized() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID category = fixtures().insertCategory(fx.workspaceId(), null, "Qualquer", "EXPENSE", false);

		mvc.perform(get(base(fx.workspaceId()))).andExpect(status().isUnauthorized());
		mvc.perform(get(base(fx.workspaceId()) + "/" + category)).andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedAnswersAreNeverCached() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		MvcResult result = mvc.perform(get(base(fx.workspaceId())).cookie(session(fx.token())))
				.andExpect(status().isOk()).andReturn();

		assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
	}

}
