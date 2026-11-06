package com.tostao.tostao_api.workspace;

import static com.tostao.tostao_api.support.AuthSupport.newEmail;
import static com.tostao.tostao_api.support.AuthSupport.register;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.support.AuthSupport.tokenOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/** Isolamento pela API: duas pessoas, cada uma com o seu workspace. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TenantIsolationTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;

	record Person(String token, UUID workspaceId) {
	}

	private Person newPerson() throws Exception {
		String email = newEmail();
		MvcResult result = register(mvc, email);
		UUID workspace = owner.client().sql("""
				select m.workspace_id from workspace_members m join users u on u.id = m.user_id
				where u.email = :e""").param("e", email).query(UUID.class).single();
		return new Person(tokenOf(result), workspace);
	}

	/** O corpo de erro ecoa a rota pedida em {@code instance}; fora isso, tem que ser idêntico. */
	private static String withoutInstance(String body) {
		return body.replaceAll("\"instance\":\"[^\"]*\",?", "").replace(",}", "}");
	}

	@Test
	void membersCanReadTheirOwnWorkspace() throws Exception {
		Person me = newPerson();

		mvc.perform(get("/api/v1/workspaces/" + me.workspaceId()).cookie(session(me.token())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(me.workspaceId().toString()))
				.andExpect(jsonPath("$.role").value("OWNER"))
				.andExpect(jsonPath("$.baseCurrency").value("BRL"));
	}

	@Test
	void anotherPersonsWorkspaceAndANonexistentOneAreIndistinguishable() throws Exception {
		Person me = newPerson();
		Person other = newPerson();

		MvcResult alheio = mvc.perform(get("/api/v1/workspaces/" + other.workspaceId()).cookie(session(me.token())))
				.andExpect(status().isNotFound()).andReturn();
		MvcResult inexistente = mvc.perform(get("/api/v1/workspaces/" + UUID.randomUUID()).cookie(session(me.token())))
				.andExpect(status().isNotFound()).andReturn();

		assertThat(withoutInstance(alheio.getResponse().getContentAsString()))
				.isEqualTo(withoutInstance(inexistente.getResponse().getContentAsString()));
		assertThat(alheio.getResponse().getContentAsString()).doesNotContain(other.workspaceId() + "\",\"name");
	}

	@Test
	void theOtherPersonStillReadsTheirOwnWorkspace() throws Exception {
		Person me = newPerson();
		Person other = newPerson();

		mvc.perform(get("/api/v1/workspaces/" + other.workspaceId()).cookie(session(other.token())))
				.andExpect(status().isOk());
		mvc.perform(get("/api/v1/workspaces/" + me.workspaceId()).cookie(session(other.token())))
				.andExpect(status().isNotFound());
	}

	@Test
	void requiresASession() throws Exception {
		Person me = newPerson();

		MvcResult result = mvc.perform(get("/api/v1/workspaces/" + me.workspaceId()))
				.andExpect(status().isUnauthorized()).andReturn();

		assertThat(result.getResponse().getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
	}

}
