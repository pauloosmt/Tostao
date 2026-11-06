package com.tostao.tostao_api.auth;

import static com.tostao.tostao_api.support.AuthSupport.PASSWORD;
import static com.tostao.tostao_api.support.AuthSupport.login;
import static com.tostao.tostao_api.support.AuthSupport.newEmail;
import static com.tostao.tostao_api.support.AuthSupport.registerAndGetToken;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.support.AuthSupport.tokenOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LogoutAllTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;

	private int versionOf(String email) {
		return owner.client().sql("select session_version from users where email = :e").param("e", email)
				.query(Integer.class).single();
	}

	@Test
	void signingOutOfAllDevicesInvalidatesEverySessionOfThatPerson() throws Exception {
		String email = newEmail();
		String phone = registerAndGetToken(mvc, email);
		String laptop = tokenOf(login(mvc, email, PASSWORD));

		MvcResult result = mvc.perform(post("/api/v1/auth/logout-all").cookie(session(phone)))
				.andExpect(status().isNoContent()).andReturn();

		assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
		mvc.perform(get("/api/v1/me").cookie(session(laptop))).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/me").cookie(session(phone))).andExpect(status().isUnauthorized());
		assertThat(versionOf(email)).isEqualTo(1);
	}

	@Test
	void signingInAgainAfterwardsWorksWithTheNewVersion() throws Exception {
		String email = newEmail();
		String phone = registerAndGetToken(mvc, email);
		mvc.perform(post("/api/v1/auth/logout-all").cookie(session(phone))).andExpect(status().isNoContent());

		MvcResult again = login(mvc, email, PASSWORD);

		assertThat(again.getResponse().getStatus()).isEqualTo(200);
		mvc.perform(get("/api/v1/me").cookie(session(tokenOf(again)))).andExpect(status().isOk());
	}

	@Test
	void requiresASession() throws Exception {
		mvc.perform(post("/api/v1/auth/logout-all")).andExpect(status().isUnauthorized());
	}

	@Test
	void doesNotAffectAnotherPersonsSession() throws Exception {
		String mine = registerAndGetToken(mvc, newEmail());
		String theirs = registerAndGetToken(mvc, newEmail());

		mvc.perform(post("/api/v1/auth/logout-all").cookie(session(mine))).andExpect(status().isNoContent());

		mvc.perform(get("/api/v1/me").cookie(session(theirs))).andExpect(status().isOk());
	}

}
