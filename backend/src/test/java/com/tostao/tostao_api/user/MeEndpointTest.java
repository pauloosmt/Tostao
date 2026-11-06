package com.tostao.tostao_api.user;

import static com.tostao.tostao_api.support.AuthSupport.newEmail;
import static com.tostao.tostao_api.support.AuthSupport.registerAndGetToken;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MeEndpointTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	@Test
	void returnsNameEmailAndWorkspacesWithRoleAndNothingSensitive() throws Exception {
		String email = newEmail();
		String token = registerAndGetToken(mvc, email);

		MvcResult result = mvc.perform(get("/api/v1/me").cookie(session(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Teste"))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.workspaces.length()").value(1))
				.andExpect(jsonPath("$.workspaces[0].role").value("OWNER"))
				.andExpect(jsonPath("$.workspaces[0].name").value("Pessoal"))
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andReturn();

		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("hash")
				.doesNotContainIgnoringCase("session_version").doesNotContainIgnoringCase("sessionVersion")
				.doesNotContain(token);
	}

	@Test
	void requiresASession() throws Exception {
		mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void personWithoutAnyWorkspaceGetsAnEmptyListAndNoWorkspaceAccess() throws Exception {
		UUID orphan = owner.client()
				.sql("insert into users (email, password_hash, name) values (:e, 'x', 'Sem workspace') returning id")
				.param("e", newEmail()).query(UUID.class).single();
		String token = tokens.issue(orphan, 0);

		mvc.perform(get("/api/v1/me").cookie(session(token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.workspaces.length()").value(0));
		mvc.perform(get("/api/v1/workspaces/" + UUID.randomUUID()).cookie(session(token)))
				.andExpect(status().isNotFound());
	}

}
