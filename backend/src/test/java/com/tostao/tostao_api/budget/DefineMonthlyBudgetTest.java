package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.body;
import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.define;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** História 1: definir (criar ou substituir) o limite de uma categoria de despesa ou o geral num mês. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DefineMonthlyBudgetTest {

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
		return fixtures().countMonthlyBudgets(workspaceId);
	}

	private static String idOf(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

	@Test
	void definesTheLimitOfAnExpenseCategoryForAMonth() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();

		ResultActions result = define(mvc, s.owner(), body("2026-11", s.food(), 64_000)).andExpect(status().isCreated())
				.andExpect(header().string("Location", containsString("/api/v1/workspaces/" + w + "/monthly-budgets/")))
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(jsonPath("$.month").value("2026-11"))
				.andExpect(jsonPath("$.categoryId").value(s.food().toString()))
				.andExpect(jsonPath("$.categoryName").value("Alimentação"))
				.andExpect(jsonPath("$.amountCents").value(64_000))
				.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString()))
				.andExpect(jsonPath("$.createdAt").exists()).andExpect(jsonPath("$.updatedAt").exists());
		String id = idOf(result);

		assertThat(result.andReturn().getResponse().getHeader("Location")).endsWith("/monthly-budgets/" + id);
		assertThat(owner.client().sql("select month_start from monthly_budgets where id = :id")
				.param("id", UUID.fromString(id)).query(LocalDate.class).single()).isEqualTo(LocalDate.of(2026, 11, 1));
		assertThat(rows(w)).isEqualTo(1);
	}

	@Test
	void withoutACategoryTheLimitIsTheGeneralOneOfTheMonth() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());

		define(mvc, s.owner(), body("2026-11", null, 400_000)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.categoryId").doesNotExist()).andExpect(jsonPath("$.categoryName").doesNotExist());
		// null explícito também é o geral, e é o mesmo escopo: substitui em vez de duplicar
		define(mvc, s.owner(), "{\"month\":\"2026-11\",\"categoryId\":null,\"amountCents\":500000}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.amountCents").value(500_000));
		assertThat(rows(s.owner().workspaceId())).isEqualTo(1);
	}

	@Test
	void theSameScopeAndMonthIsReplacedKeepingTheCreatorAndTheCreationDate() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		String id = idOf(define(mvc, s.owner(), body("2026-11", s.food(), 64_000)).andExpect(status().isCreated()));
		String created = JsonPath.read(define(mvc, s.owner(), body("2026-11", s.food(), 64_000))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id)).andReturn().getResponse()
				.getContentAsString(), "$.createdAt");

		define(mvc, s.owner(), body("2026-11", s.food(), 70_000)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.amountCents").value(70_000))
				.andExpect(jsonPath("$.createdAt").value(created))
				.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString()));
		assertThat(rows(w)).isEqualTo(1);
	}

	@Test
	void anEditorReplacingAnotherPersonsLimitNeverChangesTheCreator() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		Fixture editor = fixtures().addMember(w, "EDITOR");
		define(mvc, s.owner(), body("2026-11", s.food(), 64_000)).andExpect(status().isCreated());

		define(mvc, editor, body("2026-11", s.food(), 1_000)).andExpect(status().isOk())
				.andExpect(jsonPath("$.amountCents").value(1_000))
				.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString()));
		// e o que o editor cria é dele
		define(mvc, editor, body("2026-11", s.transport(), 5_000)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.createdBy").value(editor.userId().toString()));
	}

	@Test
	void aPrincipalAndItsSubcategoryHaveIndependentLimitsAndOtherMonthsAreOtherRows() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();

		define(mvc, s.owner(), body("2026-11", s.food(), 64_000)).andExpect(status().isCreated());
		define(mvc, s.owner(), body("2026-11", s.market(), 90_000)).andExpect(status().isCreated());
		define(mvc, s.owner(), body("2026-12", s.food(), 64_000)).andExpect(status().isCreated());

		assertThat(rows(w)).isEqualTo(3);
	}

	@Test
	void zeroAndTheMaximumAreAccepted() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());

		define(mvc, s.owner(), body("2026-11", s.leisure(), 0)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.amountCents").value(0));
		define(mvc, s.owner(), body("2026-11", s.transport(), 1_000_000_000_000L)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.amountCents").value(1_000_000_000_000L));
	}

	@Test
	void aBadMonthIsRejectedNamingTheFieldAndNothingIsWritten() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());

		for (String bad : new String[] { "2026-1", "202611", "2026-13", "2026-00", "1999-12", "2101-01", "2026-11-01" }) {
			define(mvc, s.owner(), body(bad, s.food(), 100)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("month"));
		}
		define(mvc, s.owner(), "{\"categoryId\":\"" + s.food() + "\",\"amountCents\":100}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("month"));
		assertThat(rows(s.owner().workspaceId())).isZero();
	}

	@Test
	void aBadAmountIsRejectedNamingTheFieldAndNothingIsWritten() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());

		for (String amount : new String[] { "-1", "1000000000001", "10.5", "null", "true" }) {
			define(mvc, s.owner(), "{\"month\":\"2026-11\",\"amountCents\":" + amount + "}")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("amountCents"));
		}
		define(mvc, s.owner(), "{\"month\":\"2026-11\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("amountCents"));
		assertThat(rows(s.owner().workspaceId())).isZero();
	}

	@Test
	void anIncomeCategoryAnArchivedOneAndOthersAreRefused() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		MonthlyBudgetScenario other = MonthlyBudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();

		define(mvc, s.owner(), body("2026-11", s.salary(), 100)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("categoryId"));
		define(mvc, s.owner(), body("2026-11", other.food(), 100)).andExpect(status().isNotFound());
		define(mvc, s.owner(), body("2026-11", UUID.randomUUID(), 100)).andExpect(status().isNotFound());
		for (String bad : new String[] { "\"abc\"", "\"\"", "7" }) {
			define(mvc, s.owner(), "{\"month\":\"2026-11\",\"categoryId\":" + bad + ",\"amountCents\":100}")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("categoryId"));
		}
		owner.client().sql("update categories set archived = true where id = :id").param("id", s.food()).update();
		define(mvc, s.owner(), body("2026-11", s.food(), 100)).andExpect(status().isConflict());

		assertThat(rows(w)).isZero();
	}

	@Test
	void aWorkspaceWithoutExpenseCategoriesStillAcceptsTheGeneralLimit() throws Exception {
		Fixture lonely = fixtures().workspaceWithOwner();

		define(mvc, lonely, body("2026-11", null, 100_000)).andExpect(status().isCreated());
	}

	@Test
	void fieldsOutsideTheAllowedListAreRejected() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());

		for (String extra : new String[] { "\"workspaceId\":\"" + UUID.randomUUID() + "\"",
				"\"createdBy\":\"" + UUID.randomUUID() + "\"", "\"createdAt\":\"2026-01-01T00:00:00Z\"",
				"\"updatedAt\":\"2026-01-01T00:00:00Z\"", "\"id\":\"" + UUID.randomUUID() + "\"", "\"week\":\"2026-W45\"" }) {
			define(mvc, s.owner(), "{\"month\":\"2026-11\",\"amountCents\":100," + extra + "}")
					.andExpect(status().isBadRequest());
		}
		assertThat(rows(s.owner().workspaceId())).isZero();
	}

	@Test
	void aMonthlyLimitAndAWeeklyOneForTheSameCategoryDoNotTouchEachOther() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		fixtures().insertWeeklyBudget(w, LocalDate.of(2026, 11, 2), s.food(), 16_000, s.owner().userId());
		String weeklyBefore = MonthlyBudgetScenario.weeklyBudgetsFingerprint(owner, w);

		define(mvc, s.owner(), body("2026-11", s.food(), 64_000)).andExpect(status().isCreated());
		define(mvc, s.owner(), body("2026-11", s.food(), 70_000)).andExpect(status().isOk());

		assertThat(MonthlyBudgetScenario.weeklyBudgetsFingerprint(owner, w)).isEqualTo(weeklyBefore);
		assertThat(fixtures().countWeeklyBudgets(w)).isEqualTo(1);
		assertThat(rows(w)).isEqualTo(1);
	}

}
