package com.tostao.tostao_api.account;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
class CreateAccountTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private MvcResult create(Fixture fx, String json) throws Exception {
		return mvc.perform(post("/api/v1/workspaces/" + fx.workspaceId() + "/accounts")
				.cookie(session(fx.token())).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
	}

	private static String account(String name, String type, long initialCents) {
		return "{\"name\":\"%s\",\"type\":\"%s\",\"initialBalanceCents\":%d}".formatted(name, type, initialCents);
	}

	private void assertInvalid(Fixture fx, String json, String field) throws Exception {
		MvcResult result = create(fx, json);
		assertThat(result.getResponse().getStatus()).as(json).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).as(json).contains("\"field\":\"" + field + "\"");
	}

	@Test
	void createsOneAccountOfEachTypeWithBrlAndInitialBalanceAsBalance() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		for (String type : new String[] { "CHECKING", "SAVINGS", "CASH", "CREDIT_CARD", "INVESTMENT" }) {
			mvc.perform(post("/api/v1/workspaces/" + fx.workspaceId() + "/accounts").cookie(session(fx.token()))
					.contentType(MediaType.APPLICATION_JSON).content(account("Conta " + type, type, 12345)))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.type").value(type))
					.andExpect(jsonPath("$.currency").value("BRL"))
					.andExpect(jsonPath("$.archived").value(false))
					.andExpect(jsonPath("$.initialBalanceCents").value(12345))
					.andExpect(jsonPath("$.balanceCents").value(12345))
					.andExpect(jsonPath("$.createdAt").isNotEmpty())
					.andExpect(jsonPath("$.updatedAt").isNotEmpty());
		}
	}

	@Test
	void responseHasLocationAndDoesNotExposeTheWorkspaceId() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		MvcResult result = create(fx, account("Nubank", "CHECKING", 0));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		String id = result.getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
		assertThat(result.getResponse().getHeader("Location"))
				.isEqualTo("/api/v1/workspaces/" + fx.workspaceId() + "/accounts/" + id);
		assertThat(result.getResponse().getContentAsString()).doesNotContainIgnoringCase("workspace");
		assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
	}

	@Test
	void creditCardKeepsLimitAndBothDays() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		String json = """
				{"name":"Cartão","type":"CREDIT_CARD","initialBalanceCents":-25050,
				 "creditLimitCents":500000,"closingDay":10,"dueDay":17}""";

		MvcResult result = create(fx, json);

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		String body = result.getResponse().getContentAsString();
		assertThat(body).contains("\"creditLimitCents\":500000").contains("\"closingDay\":10")
				.contains("\"dueDay\":17").contains("\"initialBalanceCents\":-25050");
	}

	@Test
	void creditCardWithoutLimitOrDaysIsAccepted() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		assertThat(create(fx, account("Cartão simples", "CREDIT_CARD", 0)).getResponse().getStatus()).isEqualTo(201);
	}

	@Test
	void cardFieldsOnOtherTypesAreRefusedNamingTheField() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		assertInvalid(fx, "{\"name\":\"A\",\"type\":\"CHECKING\",\"initialBalanceCents\":0,\"creditLimitCents\":1000}",
				"creditLimitCents");
		assertInvalid(fx, "{\"name\":\"B\",\"type\":\"SAVINGS\",\"initialBalanceCents\":0,\"closingDay\":5}",
				"closingDay");
		assertInvalid(fx, "{\"name\":\"C\",\"type\":\"CASH\",\"initialBalanceCents\":0,\"dueDay\":5}", "dueDay");
		long created = owner.client().sql("select count(*) from accounts where workspace_id = :w")
				.param("w", fx.workspaceId()).query(Long.class).single();
		assertThat(created).isZero();
	}

	@Test
	void invalidValuesAreRefusedAndNothingIsCreated() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		assertInvalid(fx, account("", "CHECKING", 0), "name");
		assertInvalid(fx, account("     ", "CHECKING", 0), "name");
		assertInvalid(fx, account("n".repeat(101), "CHECKING", 0), "name");
		assertInvalid(fx, account("Ok", "CHECKING", 1_000_000_000_001L), "initialBalanceCents");
		assertInvalid(fx, account("Ok", "CHECKING", -1_000_000_000_001L), "initialBalanceCents");
		assertInvalid(fx, "{\"name\":\"Ok\",\"type\":\"CREDIT_CARD\",\"initialBalanceCents\":0,\"creditLimitCents\":-1}",
				"creditLimitCents");
		assertInvalid(fx, "{\"name\":\"Ok\",\"type\":\"CREDIT_CARD\",\"initialBalanceCents\":0,\"closingDay\":0}",
				"closingDay");
		assertInvalid(fx, "{\"name\":\"Ok\",\"type\":\"CREDIT_CARD\",\"initialBalanceCents\":0,\"dueDay\":32}",
				"dueDay");
		assertInvalid(fx, "{\"type\":\"CHECKING\",\"initialBalanceCents\":0}", "name");
		assertInvalid(fx, "{\"name\":\"Ok\",\"initialBalanceCents\":0}", "type");
		assertInvalid(fx, "{\"name\":\"Ok\",\"type\":\"CHECKING\"}", "initialBalanceCents");
	}

	@Test
	void unknownTypeNonIntegerMoneyAndExtraFieldsAreRefused() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		String[] bodies = {
				"{\"name\":\"Ok\",\"type\":\"POUPANCA\",\"initialBalanceCents\":0}",
				"{\"name\":\"Ok\",\"type\":\"CHECKING\",\"initialBalanceCents\":10.5}",
				"{\"name\":\"Ok\",\"type\":\"CHECKING\",\"initialBalanceCents\":1e3}",
				"{\"name\":\"Ok\",\"type\":\"CHECKING\",\"initialBalanceCents\":0,\"workspaceId\":\"" + UUID.randomUUID() + "\"}",
				"{\"name\":\"Ok\",\"type\":\"CHECKING\",\"initialBalanceCents\":0,\"archived\":true}",
				"{\"name\":\"Ok\",\"type\":\"CHECKING\",\"initialBalanceCents\":0,\"balanceCents\":999}",
				"{\"name\":\"Ok\",\"type\":\"CHECKING\",\"initialBalanceCents\":0,\"createdAt\":\"2020-01-01T00:00:00Z\"}",
				"{\"name\":\"Ok\",\"type\":\"CHECKING\",\"initialBalanceCents\":0,\"currency\":\"USD\"}" };
		for (String body : bodies) {
			assertThat(create(fx, body).getResponse().getStatus()).as(body).isEqualTo(400);
		}
		long created = owner.client().sql("select count(*) from accounts where workspace_id = :w")
				.param("w", fx.workspaceId()).query(Long.class).single();
		assertThat(created).isZero();
	}

	@Test
	void negativeInitialBalanceIsAcceptedOnAnyType() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		MvcResult result = create(fx, account("Cheque especial", "CHECKING", -50000));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat(result.getResponse().getContentAsString()).contains("\"balanceCents\":-50000");
	}

	@Test
	void nameIsStoredWithoutSurroundingSpaces() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		MvcResult result = create(fx, account("  Itaú  ", "CHECKING", 0));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat(result.getResponse().getContentAsString()).contains("\"name\":\"Itaú\"");
	}

	@Test
	void duplicateActiveNameIsRefusedRegardlessOfCaseAndSpaces() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		assertThat(create(fx, account("Nubank", "CHECKING", 0)).getResponse().getStatus()).isEqualTo(201);

		MvcResult duplicate = create(fx, account("nubank ", "SAVINGS", 0));

		assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
		assertThat(duplicate.getResponse().getContentType()).contains("application/problem+json");
		long count = owner.client().sql("select count(*) from accounts where workspace_id = :w")
				.param("w", fx.workspaceId()).query(Long.class).single();
		assertThat(count).isEqualTo(1);
	}

	@Test
	void sameNameInAnotherWorkspaceIsAllowed() throws Exception {
		Fixture one = fixtures().workspaceWithOwner();
		Fixture two = fixtures().workspaceWithOwner();

		assertThat(create(one, account("Nubank", "CHECKING", 0)).getResponse().getStatus()).isEqualTo(201);
		assertThat(create(two, account("Nubank", "CHECKING", 0)).getResponse().getStatus()).isEqualTo(201);
	}

	@Test
	void emptyWorkspaceListsAnEmptyArray() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts").cookie(session(fx.token())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void listIsOrderedByNameIgnoringCase() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		for (String name : new String[] { "banco B", "Banco A", "carteira", "Acme" }) {
			create(fx, account(name, "CHECKING", 0));
		}

		mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts").cookie(session(fx.token())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].name").value("Acme"))
				.andExpect(jsonPath("$[1].name").value("Banco A"))
				.andExpect(jsonPath("$[2].name").value("banco B"))
				.andExpect(jsonPath("$[3].name").value("carteira"));
	}

	@Test
	void concurrentCreationsOfTheSameNameYieldOneCreatedAndNoServerError() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		int threads = 6;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Callable<Integer>> calls = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				calls.add(() -> create(fx, account("Concorrente", "CHECKING", 0)).getResponse().getStatus());
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : pool.invokeAll(calls)) {
				statuses.add(f.get());
			}
			assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
			assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);
		}
		finally {
			pool.shutdownNow();
		}
	}

}
