package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.base;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** História 5: só OWNER e EDITOR alteram, VIEWER só lê, e cada workspace só vê o seu (as cinco rotas). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MonthlyBudgetAccessTest {

	private static final LocalDate NOV = LocalDate.of(2026, 11, 1);

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private MonthlyBudgetScenario seeded() {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		s.seedQuickstartBudgets(fixtures());
		return s;
	}

	private UUID limitId(UUID workspaceId, UUID category) {
		return owner.client().sql("select id from monthly_budgets where workspace_id = :w and month_start = :m and category_id = :c")
				.param("w", workspaceId).param("m", NOV).param("c", category).query(UUID.class).single();
	}

	/** As cinco rotas, como a pessoa dada (ou sem sessão, com {@code who} nulo), sobre o workspace e o limite dados. */
	private ResultActions[] fiveRoutes(UUID workspaceId, Fixture who, String limitId, UUID category) throws Exception {
		String base = base(workspaceId);
		MockHttpServletRequestBuilder[] requests = {
				get(base + "?month=2026-11"),
				put(base).contentType(MediaType.APPLICATION_JSON)
						.content("{\"month\":\"2026-12\",\"categoryId\":\"" + category + "\",\"amountCents\":100}"),
				put(base + "/" + limitId).contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":1}"),
				delete(base + "/" + limitId),
				post(base + "/copy").contentType(MediaType.APPLICATION_JSON)
						.content("{\"fromMonth\":\"2026-11\",\"months\":1}") };
		ResultActions[] results = new ResultActions[requests.length];
		for (int i = 0; i < requests.length; i++) {
			results[i] = mvc.perform(who == null ? requests[i] : requests[i].cookie(session(who.token())));
		}
		return results;
	}

	@Test
	void aNonMemberGetsTheSameNotFoundAsAMissingWorkspaceOnEveryRouteAndNothingChanges() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		Fixture outsider = fixtures().workspaceWithOwner();
		String id = limitId(w, s.food()).toString();
		String monthlyBefore = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w);
		String weeklyBefore = MonthlyBudgetScenario.weeklyBudgetsFingerprint(owner, w);
		String financeBefore = MonthlyBudgetScenario.financeFingerprint(owner, w);

		ResultActions[] real = fiveRoutes(w, outsider, id, s.food());
		ResultActions[] missing = fiveRoutes(UUID.randomUUID(), outsider, id, s.food());
		for (int i = 0; i < 5; i++) {
			real[i].andExpect(status().isNotFound());
			missing[i].andExpect(status().isNotFound());
			String a = real[i].andReturn().getResponse().getContentAsString();
			String b = missing[i].andReturn().getResponse().getContentAsString();
			assertThat((String) JsonPath.read(a, "$.title")).as("rota %d".formatted(i)).isEqualTo(JsonPath.read(b, "$.title"));
			assertThat((String) JsonPath.read(a, "$.detail")).as("rota %d".formatted(i)).isEqualTo(JsonPath.read(b, "$.detail"));
		}
		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w)).isEqualTo(monthlyBefore);
		assertThat(MonthlyBudgetScenario.weeklyBudgetsFingerprint(owner, w)).isEqualTo(weeklyBefore);
		assertThat(MonthlyBudgetScenario.financeFingerprint(owner, w)).isEqualTo(financeBefore);
	}

	@Test
	void aViewerReadsEverythingButCannotWriteAnything() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		Fixture viewer = fixtures().addMember(w, "VIEWER");
		String id = limitId(w, s.food()).toString();
		String before = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w);

		ResultActions[] routes = fiveRoutes(w, viewer, id, s.food());
		routes[0].andExpect(status().isOk()).andExpect(jsonPath("$.budgets.length()").value(4));
		for (int i = 1; i < 5; i++) {
			// "sem permissão" é distinto de "não encontrado"
			routes[i].andExpect(status().isForbidden());
		}
		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w)).isEqualTo(before);
	}

	@Test
	void ownersAndEditorsWriteOnEveryRouteEvenOnLimitsOfAnotherPerson() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		Fixture editor = fixtures().addMember(w, "EDITOR");
		String id = limitId(w, s.food()).toString();

		ResultActions[] asEditor = fiveRoutes(w, editor, id, s.leisure());
		asEditor[0].andExpect(status().isOk());
		asEditor[1].andExpect(status().isCreated());
		asEditor[2].andExpect(status().isOk()).andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString()));
		asEditor[3].andExpect(status().isNoContent());
		asEditor[4].andExpect(status().isOk());
		// o dono também escreve
		ResultActions[] asOwner = fiveRoutes(w, s.owner(), limitId(w, s.market()).toString(), s.transport());
		asOwner[1].andExpect(status().isOk());
		asOwner[2].andExpect(status().isOk());
		asOwner[3].andExpect(status().isNoContent());
	}

	@Test
	void withoutASessionEveryRouteIsRefused() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();

		for (ResultActions result : fiveRoutes(w, null, limitId(w, s.food()).toString(), s.food())) {
			result.andExpect(status().isUnauthorized());
		}
	}

	@Test
	void aLimitOfAnotherWorkspaceIsNeverReachedByItsId() throws Exception {
		MonthlyBudgetScenario a = seeded();
		MonthlyBudgetScenario b = seeded();
		UUID foreign = limitId(b.owner().workspaceId(), b.food());
		UUID wa = a.owner().workspaceId();
		String before = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, b.owner().workspaceId());

		MonthlyBudgetScenario.update(mvc, wa, a.owner(), foreign.toString(), "{\"amountCents\":1}")
				.andExpect(status().isNotFound());
		MonthlyBudgetScenario.remove(mvc, wa, a.owner(), foreign.toString()).andExpect(status().isNotFound());
		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, b.owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void aCategoryOfAnotherWorkspaceUnknownWorkspacesAndMalformedIdsAreNotFound() throws Exception {
		MonthlyBudgetScenario a = seeded();
		MonthlyBudgetScenario b = seeded();
		UUID wa = a.owner().workspaceId();
		long before = fixtures().countMonthlyBudgets(wa);

		MonthlyBudgetScenario.define(mvc, wa, a.owner(), MonthlyBudgetScenario.body("2026-12", b.food(), 100))
				.andExpect(status().isNotFound());
		assertThat(fixtures().countMonthlyBudgets(wa)).isEqualTo(before);
		MonthlyBudgetScenario.month(mvc, UUID.randomUUID(), a.owner(), "").andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/workspaces/abc/monthly-budgets").cookie(session(a.owner().token())))
				.andExpect(status().isNotFound());
	}

	@Test
	void theTrackingOfOneWorkspaceNeverSumsTheOthersSpending() throws Exception {
		MonthlyBudgetScenario a = seeded();
		MonthlyBudgetScenario b = seeded();
		fixtures().insertLedgerTransaction(b.owner().workspaceId(), b.checking(), b.owner().userId(), b.food(), "EXPENSE",
				700_000, LocalDate.of(2026, 11, 3), "Só de B", false);

		MonthlyBudgetScenario.month(mvc, a.owner(), "month=2026-11").andExpect(jsonPath("$.totalSpentCents").value(104_000));
		MonthlyBudgetScenario.month(mvc, b.owner(), "month=2026-11")
				.andExpect(jsonPath("$.totalSpentCents").value(804_000));
	}

}
