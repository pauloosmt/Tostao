package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.BudgetScenario.base;
import static com.tostao.tostao_api.budget.BudgetScenario.body;
import static com.tostao.tostao_api.budget.BudgetScenario.copy;
import static com.tostao.tostao_api.budget.BudgetScenario.define;
import static com.tostao.tostao_api.budget.BudgetScenario.remove;
import static com.tostao.tostao_api.budget.BudgetScenario.update;
import static com.tostao.tostao_api.budget.BudgetScenario.week;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** História 5: só OWNER e EDITOR alteram, VIEWER só lê, quem não é membro não descobre nada. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BudgetAccessTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	/** As cinco rotas do orçamento, cada uma com um corpo válido, para o workspace e o limite dados. */
	private Map<String, BiFunction<Fixture, UUID, ResultActions>> routes(BudgetScenario s, UUID budgetId) {
		Map<String, BiFunction<Fixture, UUID, ResultActions>> routes = new LinkedHashMap<>();
		routes.put("GET", (who, w) -> uncheck(() -> week(mvc, w, who, "week=2026-W45")));
		routes.put("PUT /budgets", (who, w) -> uncheck(() -> define(mvc, w, who, body("2026-W46", s.food(), 1_000))));
		routes.put("PUT /{id}", (who, w) -> uncheck(() -> update(mvc, w, who, budgetId.toString(), "{\"amountCents\":500}")));
		routes.put("DELETE /{id}", (who, w) -> uncheck(() -> remove(mvc, w, who, budgetId.toString())));
		routes.put("POST /copy", (who, w) -> uncheck(() -> copy(mvc, w, who, "{\"fromWeek\":\"2026-W45\",\"weeks\":2}")));
		return routes;
	}

	private interface Call {
		ResultActions run() throws Exception;
	}

	private static ResultActions uncheck(Call call) {
		try {
			return call.run();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	/** Corpo sem o campo {@code instance}, que repete a rota pedida e por isso muda de um caso para outro. */
	private static String withoutInstance(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", "");
	}

	private String allDataFingerprint(UUID workspaceId) {
		return BudgetScenario.budgetsFingerprint(owner, workspaceId) + BudgetScenario.financeFingerprint(owner, workspaceId);
	}

	@Test
	void aNonMemberGetsTheSameNotFoundAsAMissingWorkspaceOnEveryRoute() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID id = fixtures.insertWeeklyBudget(s.owner().workspaceId(), BudgetScenario.W45, s.food(), 16_000,
				s.owner().userId());
		Fixture stranger = fixtures.workspaceWithOwner();
		String before = allDataFingerprint(s.owner().workspaceId());

		for (var route : routes(s, id).entrySet()) {
			MvcResult foreign = route.getValue().apply(stranger, s.owner().workspaceId())
					.andExpect(status().isNotFound()).andReturn();
			MvcResult missing = route.getValue().apply(stranger, UUID.randomUUID()).andExpect(status().isNotFound())
					.andReturn();

			assertThat(withoutInstance(foreign)).as(route.getKey()).isEqualTo(withoutInstance(missing));
			assertThat(withoutInstance(foreign)).as(route.getKey()).doesNotContain("Alimentação")
					.doesNotContain("budgets").doesNotContain("totalSpentCents");
		}

		assertThat(allDataFingerprint(s.owner().workspaceId())).isEqualTo(before);
		assertThat(fixtures.countWeeklyBudgets(stranger.workspaceId())).isZero();
	}

	@Test
	void aViewerReadsButEveryChangeIsRefusedForLackOfPermissionAndChangesNothing() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		s.seedQuickstartBudgets(fixtures);
		UUID id = owner.client().sql("select id from weekly_budgets where workspace_id = :w and category_id = :c")
				.param("w", w).param("c", s.food()).query(UUID.class).single();
		Fixture viewer = fixtures.addMember(w, "VIEWER");
		String before = allDataFingerprint(w);

		for (var route : routes(s, id).entrySet()) {
			if (route.getKey().equals("GET")) {
				route.getValue().apply(viewer, w).andExpect(status().isOk())
						.andExpect(jsonPath("$.totalSpentCents").value(26_000))
						.andExpect(jsonPath("$.budgets.length()").value(4));
			} else {
				route.getValue().apply(viewer, w).andExpect(status().isForbidden())
						.andExpect(jsonPath("$.title").value("Sem permissão"));
			}
		}

		assertThat(allDataFingerprint(w)).isEqualTo(before);
	}

	@Test
	void ownersAndEditorsChangeLimitsEvenOnesCreatedByOthers() throws Exception {
		for (String role : new String[] { "OWNER", "EDITOR" }) {
			WorkspaceFixtures fixtures = fixtures();
			BudgetScenario s = BudgetScenario.create(fixtures);
			UUID w = s.owner().workspaceId();
			s.seedQuickstartBudgets(fixtures);
			Fixture who = role.equals("OWNER") ? s.owner() : fixtures.addMember(w, "EDITOR");
			UUID otherPersonsLimit = owner.client()
					.sql("select id from weekly_budgets where workspace_id = :w and category_id = :c").param("w", w)
					.param("c", s.food()).query(UUID.class).single();

			define(mvc, w, who, body("2026-W46", s.food(), 1_000)).andExpect(status().isCreated())
					.andExpect(jsonPath("$.createdBy").value(who.userId().toString()));
			update(mvc, w, who, otherPersonsLimit.toString(), "{\"amountCents\":17000}").andExpect(status().isOk())
					.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString()));
			copy(mvc, w, who, "{\"fromWeek\":\"2026-W45\",\"weeks\":1}").andExpect(status().isOk())
					.andExpect(jsonPath("$.created").value(3)).andExpect(jsonPath("$.skippedExisting").value(1));
			remove(mvc, w, who, otherPersonsLimit.toString()).andExpect(status().isNoContent());
			week(mvc, w, who, "week=2026-W45").andExpect(status().isOk()).andExpect(jsonPath("$.budgets.length()").value(3));
		}
	}

	@Test
	void withoutASessionEveryRouteIsRefused() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());
		String url = base(s.owner().workspaceId());
		String some = UUID.randomUUID().toString();

		mvc.perform(get(url + "?week=2026-W45")).andExpect(status().isUnauthorized());
		mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON).content(body("2026-W45", null, 100)))
				.andExpect(status().isUnauthorized());
		mvc.perform(put(url + "/" + some).contentType(MediaType.APPLICATION_JSON).content("{\"amountCents\":1}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(delete(url + "/" + some)).andExpect(status().isUnauthorized());
		mvc.perform(post(url + "/copy").contentType(MediaType.APPLICATION_JSON)
				.content("{\"fromWeek\":\"2026-W45\",\"weeks\":1}")).andExpect(status().isUnauthorized());
		assertThat(fixtures().countWeeklyBudgets(s.owner().workspaceId())).isZero();
	}

	@Test
	void aLimitOfAnotherWorkspaceCannotBeReachedThroughMyWorkspaceRoute() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario mine = BudgetScenario.create(fixtures);
		BudgetScenario other = BudgetScenario.create(fixtures);
		UUID foreign = fixtures.insertWeeklyBudget(other.owner().workspaceId(), BudgetScenario.W45, other.food(),
				16_000, other.owner().userId());
		String before = allDataFingerprint(other.owner().workspaceId());

		update(mvc, mine.owner().workspaceId(), mine.owner(), foreign.toString(), "{\"amountCents\":1}")
				.andExpect(status().isNotFound());
		remove(mvc, mine.owner().workspaceId(), mine.owner(), foreign.toString()).andExpect(status().isNotFound());
		define(mvc, mine.owner(), body("2026-W45", other.food(), 100)).andExpect(status().isNotFound());

		assertThat(allDataFingerprint(other.owner().workspaceId())).isEqualTo(before);
		assertThat(fixtures.countWeeklyBudgets(mine.owner().workspaceId())).isZero();
	}

	@Test
	void theTrackingOfOneWorkspaceNeverMentionsTheOthersCategoriesOrLimits() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario first = BudgetScenario.create(fixtures);
		first.seedQuickstartBudgets(fixtures);
		BudgetScenario second = BudgetScenario.create(fixtures);
		second.seedQuickstartBudgets(fixtures);

		String json = week(mvc, first.owner(), "week=2026-W45").andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();

		assertThat(json).doesNotContain(second.food().toString()).doesNotContain(second.transport().toString())
				.doesNotContain(second.owner().userId().toString());
	}

}
