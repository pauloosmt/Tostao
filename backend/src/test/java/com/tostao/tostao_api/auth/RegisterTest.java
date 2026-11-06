package com.tostao.tostao_api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class RegisterTest {

	static final String PASSWORD = "Senha-Forte-123";

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;

	static String email() {
		return "reg-" + UUID.randomUUID() + "@example.com";
	}

	static String json(String name, String email, String password) {
		return "{\"name\":\"%s\",\"email\":\"%s\",\"password\":\"%s\"}".formatted(name, email, password);
	}

	MvcResult register(String body) throws Exception {
		return mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn();
	}

	@Test
	void createsAccountSessionAndOwnerWorkspace(CapturedOutput output) throws Exception {
		String email = email();

		MvcResult result = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
				.content(json("Paulo", email, PASSWORD)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.name").value("Paulo"))
				.andExpect(jsonPath("$.workspaces.length()").value(1))
				.andExpect(jsonPath("$.workspaces[0].role").value("OWNER"))
				.andExpect(jsonPath("$.workspaces[0].baseCurrency").value("BRL"))
				.andReturn();

		String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(cookie).startsWith("tostao_session=")
				.contains("HttpOnly").contains("SameSite=Lax").contains("Path=/api");

		String jwt = cookie.substring("tostao_session=".length(), cookie.indexOf(';'));
		String body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain(jwt).doesNotContainIgnoringCase("password").doesNotContain("$2a$");
		result.getResponse().getHeaderNames().stream()
				.filter(h -> !h.equalsIgnoreCase(HttpHeaders.SET_COOKIE))
				.forEach(h -> assertThat(result.getResponse().getHeader(h)).doesNotContain(jwt));
		assertThat(output.getAll()).doesNotContain(PASSWORD);
	}

	@Test
	void storesNormalizedEmailAndHashOnly() throws Exception {
		String email = email();

		register(json("Ana", "  " + email.toUpperCase() + "  ", PASSWORD));

		var row = owner.client().sql("select email, password_hash from users where email = :e")
				.param("e", email).query((rs, i) -> new String[] { rs.getString(1), rs.getString(2) }).single();
		assertThat(row[0]).isEqualTo(email);
		assertThat(row[1]).startsWith("$2").isNotEqualTo(PASSWORD);
	}

	@Test
	void leavesThePersonalWorkspaceWithItsDefaultCategories() throws Exception {
		String email = email();
		register(json("Cida", email, PASSWORD));

		long categories = owner.client().sql("""
				select count(*) from categories c
				join workspace_members m on m.workspace_id = c.workspace_id
				join users u on u.id = m.user_id
				where u.email = :e""").param("e", email).query(Long.class).single();

		assertThat(categories).isPositive();
	}

	@Test
	void createsExactlyOneUserWorkspaceAndMembership() throws Exception {
		String email = email();
		register(json("Bia", email, PASSWORD));

		UUID userId = owner.client().sql("select id from users where email = :e").param("e", email)
				.query(UUID.class).single();
		long members = owner.client().sql("select count(*) from workspace_members where user_id = :u and role = 'OWNER'")
				.param("u", userId).query(Long.class).single();
		long workspaces = owner.client().sql("""
				select count(*) from workspaces w join workspace_members m on m.workspace_id = w.id
				where m.user_id = :u""").param("u", userId).query(Long.class).single();

		assertThat(members).isEqualTo(1);
		assertThat(workspaces).isEqualTo(1);
	}

	@Test
	void rejectsDuplicateEmailRegardlessOfCase() throws Exception {
		String email = email();
		assertThat(register(json("Caio", email, PASSWORD)).getResponse().getStatus()).isEqualTo(201);

		MvcResult duplicate = register(json("Outro", email.toUpperCase(), PASSWORD));

		assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
		assertThat(duplicate.getResponse().getContentType()).contains("application/problem+json");
		long users = owner.client().sql("select count(*) from users where lower(email) = :e")
				.param("e", email).query(Long.class).single();
		assertThat(users).isEqualTo(1);
	}

	@Test
	void rejectsInvalidFieldsNamingTheField() throws Exception {
		assertInvalid(json("Dani", email(), "abc"), "password");
		assertInvalid(json("Dani", "nao-e-email", PASSWORD), "email");
		assertInvalid(json("", email(), PASSWORD), "name");
		assertInvalid(json("n".repeat(101), email(), PASSWORD), "name");
		assertInvalid(json("Dani", "a".repeat(250) + "@x.com", PASSWORD), "email");
		assertInvalid(json("Dani", email(), "a".repeat(72) + "1"), "password");
	}

	private void assertInvalid(String body, String field) throws Exception {
		MvcResult result = register(body);
		assertThat(result.getResponse().getStatus()).as(body).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).contains("\"field\":\"" + field + "\"");
	}

	@Test
	void rejectsUnknownFieldsInsteadOfBindingThem() throws Exception {
		String body = "{\"name\":\"Eva\",\"email\":\"%s\",\"password\":\"%s\",\"role\":\"OWNER\"}"
				.formatted(email(), PASSWORD);

		assertThat(register(body).getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void concurrentRegistrationsOfTheSameEmailCreateOneAccountAndNoServerError() throws Exception {
		String email = email();
		int threads = 6;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Callable<Integer>> calls = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				calls.add(() -> register(json("Fabi", email, PASSWORD)).getResponse().getStatus());
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(calls)) {
				statuses.add(f.get());
			}

			assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
			assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);
			long users = owner.client().sql("select count(*) from users where email = :e")
					.param("e", email).query(Long.class).single();
			assertThat(users).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
	}

}
