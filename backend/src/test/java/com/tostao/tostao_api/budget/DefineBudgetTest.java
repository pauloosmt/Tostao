package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.BudgetScenario.body;
import static com.tostao.tostao_api.budget.BudgetScenario.define;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** História 1: definir (criar ou substituir) o limite de uma categoria de despesa ou o geral numa semana. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DefineBudgetTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private long rows(UUID workspaceId) {
		return fixtures().countWeeklyBudgets(workspaceId);
	}

	private static String read(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	@Test
	void definesTheLimitOfAnExpenseCategoryForAWeek() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();

		var result = define(mvc, s.owner(), body("2026-W45", s.food(), 40_000)).andExpect(status().isCreated())
				.andExpect(header().string("Location", containsString("/api/v1/workspaces/" + w + "/budgets/")))
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(jsonPath("$.week").value("2026-W45"))
				.andExpect(jsonPath("$.categoryId").value(s.food().toString()))
				.andExpect(jsonPath("$.categoryName").value("Alimentação"))
				.andExpect(jsonPath("$.amountCents").value(40_000))
				.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString()))
				.andExpect(jsonPath("$.createdAt").exists()).andExpect(jsonPath("$.updatedAt").exists());
		String json = read(result);
		String id = JsonPath.read(json, "$.id");

		assertThat(result.andReturn().getResponse().getHeader("Location")).endsWith("/budgets/" + id);
		assertThat(owner.client().sql("select week_start from weekly_budgets where id = :id")
				.param("id", UUID.fromString(id)).query(LocalDate.class).single()).isEqualTo(LocalDate.of(2026, 11, 2));
		assertThat(rows(w)).isEqualTo(1);
	}

	@Test
	void withoutACategoryTheLimitIsTheGeneralOneOfTheWeek() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		define(mvc, s.owner(), body("2026-W45", null, 150_000)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.categoryId").value((Object) null))
				.andExpect(jsonPath("$.categoryName").value((Object) null))
				.andExpect(jsonPath("$.amountCents").value(150_000));
		// categoryId nulo explícito é o mesmo escopo geral
		define(mvc, s.owner(), "{\"week\":\"2026-W45\",\"categoryId\":null,\"amountCents\":120000}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.amountCents").value(120_000));
		assertThat(rows(s.owner().workspaceId())).isEqualTo(1);
	}

	@Test
	void definingAgainReplacesTheValueAndKeepsWhoCreatedItAndWhen() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		Fixture editor = fixtures.addMember(s.owner().workspaceId(), "EDITOR");
		String first = read(define(mvc, s.owner(), body("2026-W45", s.food(), 40_000)).andExpect(status().isCreated()));
		Instant createdAt = Instant.parse(JsonPath.read(first, "$.createdAt"));
		Instant firstUpdate = Instant.parse(JsonPath.read(first, "$.updatedAt"));

		String same = read(define(mvc, s.owner(), body("2026-W45", s.food(), 40_000)).andExpect(status().isOk()));
		String changed = read(define(mvc, editor, body("2026-W45", s.food(), 55_000)).andExpect(status().isOk())
				.andExpect(jsonPath("$.amountCents").value(55_000)));

		assertThat((String) JsonPath.read(same, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
		assertThat((String) JsonPath.read(changed, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
		assertThat((String) JsonPath.read(changed, "$.createdBy")).isEqualTo(s.owner().userId().toString());
		assertThat(Instant.parse(JsonPath.read(changed, "$.createdAt"))).isEqualTo(createdAt);
		assertThat(Instant.parse(JsonPath.read(changed, "$.updatedAt"))).isAfterOrEqualTo(firstUpdate);
		assertThat(rows(s.owner().workspaceId())).isEqualTo(1);
	}

	@Test
	void aRootAndItsSubcategoryHaveIndependentLimitsAndOtherWeeksAreOtherLimits() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		define(mvc, s.owner(), body("2026-W45", s.food(), 16_000)).andExpect(status().isCreated());
		define(mvc, s.owner(), body("2026-W45", s.market(), 5_000)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.categoryName").value("Mercado"));
		define(mvc, s.owner(), body("2026-W46", s.food(), 16_000)).andExpect(status().isCreated());
		define(mvc, s.owner(), body("2026-W45", null, 100_000)).andExpect(status().isCreated());

		assertThat(rows(s.owner().workspaceId())).isEqualTo(4);
	}

	@Test
	void zeroAndTheMaximumAreAccepted() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		define(mvc, s.owner(), body("2026-W45", s.leisure(), 0)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.amountCents").value(0));
		define(mvc, s.owner(), body("2026-W45", s.transport(), 1_000_000_000_000L)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.amountCents").value(1_000_000_000_000L));
	}

	@Test
	void theGeneralLimitWorksInAWorkspaceWithoutAnyCategory() throws Exception {
		Fixture bare = fixtures().workspaceWithOwner();

		define(mvc, bare, body("2026-W45", null, 90_000)).andExpect(status().isCreated());
		assertThat(rows(bare.workspaceId())).isEqualTo(1);
	}

	@Test
	void theFirstAndLastAcceptedWeeksAreAccepted() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		define(mvc, s.owner(), body("2000-W01", null, 100)).andExpect(status().isCreated());
		define(mvc, s.owner(), body("2100-W52", null, 100)).andExpect(status().isCreated());
		define(mvc, s.owner(), body("2026-W53", null, 100)).andExpect(status().isCreated());
		assertThat(owner.client().sql("select week_start from weekly_budgets where workspace_id = :w order by week_start")
				.param("w", s.owner().workspaceId()).query(LocalDate.class).list())
				.containsExactly(LocalDate.of(2000, 1, 3), LocalDate.of(2026, 12, 28), LocalDate.of(2100, 12, 27));
	}

	@Test
	void invalidWeeksAreRefusedNamingTheFieldAndNothingIsWritten() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		for (String week : new String[] { "2026-W5", "2025-W53", "1999-W52", "2101-W01", "2026-W00", "2026-W54",
				"2026W45", "" }) {
			define(mvc, s.owner(), body(week, s.food(), 100)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("week"));
		}
		define(mvc, s.owner(), "{\"amountCents\":100}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("week"));
		assertThat(rows(s.owner().workspaceId())).isZero();
	}

	@Test
	void invalidAmountsAreRefusedNamingTheFieldAndNothingIsWritten() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		for (String amount : new String[] { "-1", "1000000000001", "10.5", "true", "null" }) {
			define(mvc, s.owner(), "{\"week\":\"2026-W45\",\"amountCents\":" + amount + "}")
					.andExpect(status().isBadRequest());
		}
		define(mvc, s.owner(), "{\"week\":\"2026-W45\",\"amountCents\":-1}")
				.andExpect(jsonPath("$.errors[0].field").value("amountCents"));
		define(mvc, s.owner(), "{\"week\":\"2026-W45\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("amountCents"));
		assertThat(rows(s.owner().workspaceId())).isZero();
	}

	@Test
	void anIncomeCategoryIsRefusedNamingTheCategoryField() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		define(mvc, s.owner(), body("2026-W45", s.salary(), 100)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("categoryId"));
		assertThat(rows(s.owner().workspaceId())).isZero();
	}

	@Test
	void anArchivedCategoryIsRefusedAndNothingChanges() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID old = fixtures().insertCategory(w, null, "Antiga", "EXPENSE", true);
		define(mvc, s.owner(), body("2026-W45", s.food(), 40_000)).andExpect(status().isCreated());
		String before = BudgetScenario.budgetsFingerprint(owner, w);

		define(mvc, s.owner(), body("2026-W45", old, 100)).andExpect(status().isConflict());
		owner.client().sql("update categories set archived = true where id = :id").param("id", s.food()).update();
		// substituir o valor de um limite que já existe, mas cuja categoria foi arquivada, também é recusado
		define(mvc, s.owner(), body("2026-W45", s.food(), 1)).andExpect(status().isConflict());

		assertThat(BudgetScenario.budgetsFingerprint(owner, w)).isEqualTo(before);
	}

	@Test
	void aCategoryOfAnotherWorkspaceOrNonexistentIsNotFoundAndNothingIsWritten() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario mine = BudgetScenario.create(fixtures);
		BudgetScenario other = BudgetScenario.create(fixtures);

		define(mvc, mine.owner(), body("2026-W45", other.food(), 100)).andExpect(status().isNotFound());
		define(mvc, mine.owner(), body("2026-W45", UUID.randomUUID(), 100)).andExpect(status().isNotFound());
		define(mvc, mine.owner(), "{\"week\":\"2026-W45\",\"categoryId\":\"abc\",\"amountCents\":100}")
				.andExpect(status().isBadRequest());
		assertThat(rows(mine.owner().workspaceId())).isZero();
		assertThat(rows(other.owner().workspaceId())).isZero();
	}

	@Test
	void extraFieldsAreRefusedAndNothingIsWritten() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();

		for (String extra : new String[] { "\"workspaceId\":\"" + UUID.randomUUID() + "\"",
				"\"createdBy\":\"" + UUID.randomUUID() + "\"", "\"createdAt\":\"2020-01-01T00:00:00Z\"",
				"\"updatedAt\":\"2020-01-01T00:00:00Z\"", "\"id\":\"" + UUID.randomUUID() + "\"", "\"kind\":\"INCOME\"" }) {
			define(mvc, s.owner(), "{\"week\":\"2026-W45\",\"amountCents\":100," + extra + "}")
					.andExpect(status().isBadRequest());
		}
		assertThat(rows(w)).isZero();
	}

}
