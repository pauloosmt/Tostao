package com.tostao.tostao_api.account;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** Permissão por papel e isolamento por workspace nas rotas de contas que já existem. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AccountAccessTest {

	static final String BODY = "{\"name\":\"Nova\",\"type\":\"CHECKING\",\"initialBalanceCents\":100}";

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	/** O corpo de erro ecoa a rota pedida em {@code instance}; fora isso, tem que ser idêntico. */
	private static String withoutInstance(String body) {
		return body.replaceAll("\"instance\":\"[^\"]*\",?", "").replace(",}", "}");
	}

	private long accountCount(UUID workspaceId) {
		return owner.client().sql("select count(*) from accounts where workspace_id = :w")
				.param("w", workspaceId).query(Long.class).single();
	}

	private MockHttpServletRequestBuilder create(UUID workspaceId, String token) {
		return post("/api/v1/workspaces/" + workspaceId + "/accounts").cookie(session(token))
				.contentType(MediaType.APPLICATION_JSON).content(BODY);
	}

	@Test
	void nonMemberGetsNotFoundOnEveryRouteIdenticalToAMissingWorkspace() throws Exception {
		Fixture mine = fixtures().workspaceWithOwner();
		Fixture stranger = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(mine.workspaceId(), "Minha", "CHECKING", 100, false);
		UUID missing = UUID.randomUUID();

		String[][] routes = {
				{ "GET", "/accounts" },
				{ "GET", "/accounts/" + account },
				{ "GET", "/reports/balance" },
				{ "POST", "/accounts" } };
		for (String[] route : routes) {
			MvcResult real = perform(route, mine.workspaceId(), stranger.token());
			MvcResult none = perform(route, missing, stranger.token());
			assertThat(real.getResponse().getStatus()).as(route[0] + " " + route[1]).isEqualTo(404);
			assertThat(withoutInstance(real.getResponse().getContentAsString()))
					.as(route[0] + " " + route[1])
					.isEqualTo(withoutInstance(none.getResponse().getContentAsString()));
		}
		assertThat(accountCount(mine.workspaceId())).isEqualTo(1);
	}

	private MvcResult perform(String[] route, UUID workspaceId, String token) throws Exception {
		String url = "/api/v1/workspaces/" + workspaceId + route[1];
		if (route[0].equals("POST")) {
			return mvc.perform(post(url).cookie(session(token)).contentType(MediaType.APPLICATION_JSON).content(BODY))
					.andReturn();
		}
		return mvc.perform(get(url).cookie(session(token))).andReturn();
	}

	@Test
	void viewerReadsEverythingButCannotCreate() throws Exception {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture viewer = fixtures().addMember(ownerFx.workspaceId(), "VIEWER");
		UUID account = fixtures().insertAccount(ownerFx.workspaceId(), "Da casa", "CHECKING", 500, false);
		String wid = "/api/v1/workspaces/" + ownerFx.workspaceId();

		mvc.perform(get(wid + "/accounts").cookie(session(viewer.token()))).andExpect(status().isOk());
		mvc.perform(get(wid + "/accounts/" + account).cookie(session(viewer.token()))).andExpect(status().isOk());
		mvc.perform(get(wid + "/reports/balance").cookie(session(viewer.token()))).andExpect(status().isOk());

		MvcResult denied = mvc.perform(create(ownerFx.workspaceId(), viewer.token())).andReturn();
		assertThat(denied.getResponse().getStatus()).isEqualTo(403);
		assertThat(denied.getResponse().getContentType()).contains("application/problem+json");
		assertThat(accountCount(ownerFx.workspaceId())).isEqualTo(1);
	}

	@Test
	void theViewerRefusalIsDistinctFromTheNonMemberNotFound() throws Exception {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture viewer = fixtures().addMember(ownerFx.workspaceId(), "VIEWER");
		Fixture stranger = fixtures().workspaceWithOwner();

		MvcResult forbidden = mvc.perform(create(ownerFx.workspaceId(), viewer.token())).andReturn();
		MvcResult notFound = mvc.perform(create(ownerFx.workspaceId(), stranger.token())).andReturn();

		assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
		assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
		assertThat(withoutInstance(forbidden.getResponse().getContentAsString()))
				.isNotEqualTo(withoutInstance(notFound.getResponse().getContentAsString()));
	}

	@Test
	void editorAndOwnerCanCreate() throws Exception {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		Fixture editor = fixtures().addMember(ownerFx.workspaceId(), "EDITOR");

		mvc.perform(create(ownerFx.workspaceId(), ownerFx.token()).content(BODY.replace("Nova", "Do dono")))
				.andExpect(status().isCreated());
		mvc.perform(create(ownerFx.workspaceId(), editor.token()).content(BODY.replace("Nova", "Do editor")))
				.andExpect(status().isCreated());
		assertThat(accountCount(ownerFx.workspaceId())).isEqualTo(2);
	}

	@Test
	void everyRouteRequiresASession() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Qualquer", "CHECKING", 0, false);
		String wid = "/api/v1/workspaces/" + fx.workspaceId();

		mvc.perform(get(wid + "/accounts")).andExpect(status().isUnauthorized());
		mvc.perform(get(wid + "/accounts/" + account)).andExpect(status().isUnauthorized());
		mvc.perform(get(wid + "/reports/balance")).andExpect(status().isUnauthorized());
		mvc.perform(post(wid + "/accounts").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedResponsesAreNotCacheable() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		MvcResult list = mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts")
				.cookie(session(fx.token()))).andReturn();
		MvcResult created = mvc.perform(create(fx.workspaceId(), fx.token())).andReturn();

		assertThat(list.getResponse().getHeader("Cache-Control")).contains("no-store");
		assertThat(created.getResponse().getHeader("Cache-Control")).contains("no-store");
	}

	@Test
	void anAccountOfAnotherWorkspaceCannotBeReadThroughMyWorkspace() throws Exception {
		Fixture mine = fixtures().workspaceWithOwner();
		Fixture theirs = fixtures().workspaceWithOwner();
		UUID foreign = fixtures().insertAccount(theirs.workspaceId(), "Alheia", "CHECKING", 100, false);

		mvc.perform(get("/api/v1/workspaces/" + mine.workspaceId() + "/accounts/" + foreign)
				.cookie(session(mine.token()))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/workspaces/" + mine.workspaceId() + "/accounts").cookie(session(mine.token())))
				.andExpect(status().isOk());
	}

}
