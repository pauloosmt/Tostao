package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.BudgetScenario.base;
import static com.tostao.tostao_api.budget.BudgetScenario.body;
import static com.tostao.tostao_api.budget.BudgetScenario.define;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/** SC-012: tudo que é inválido é recusado indicando o campo, nada é gravado e nenhum detalhe interno vaza. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BudgetValidationTest {

	private static final String[] BAD_WEEKS = { "2026-W5", "2026W45", "2026-45", "2025-W53", "2026-W54", "2026-W00",
			"1999-W52", "2101-W01", "", " 2026-W45", "2026-w45", "2026-W45 ", "abc", "2026-W045" };

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private static void assertNoInternalDetail(MvcResult result) throws Exception {
		assertThat(result.getResponse().getContentAsString()).doesNotContain("SQL", "weekly_budgets", "Exception",
				"org.springframework", "stackTrace", "constraint");
	}

	@Test
	void invalidWeeksOnTheQueryAreRefusedNamingTheWeekField() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		for (String week : BAD_WEEKS) {
			MvcResult result = mvc.perform(get(base(s.owner().workspaceId())).param("week", week)
					.cookie(session(s.owner().token()))).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("week")).andReturn();
			assertNoInternalDetail(result);
		}
	}

	@Test
	void invalidWeeksOnDefineAreRefusedNamingTheWeekFieldAndNothingIsWritten() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());
		String before = BudgetScenario.budgetsFingerprint(owner, s.owner().workspaceId());

		for (String week : BAD_WEEKS) {
			MvcResult result = define(mvc, s.owner(), body(week, s.food(), 100)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("week")).andReturn();
			assertNoInternalDetail(result);
		}

		assertThat(BudgetScenario.budgetsFingerprint(owner, s.owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void invalidSourceWeeksOnCopyAreRefusedNamingTheFromWeekField() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);
		String before = BudgetScenario.budgetsFingerprint(owner, s.owner().workspaceId());

		for (String week : BAD_WEEKS) {
			MvcResult result = BudgetScenario.copy(mvc, s.owner().workspaceId(), s.owner(),
					"{\"fromWeek\":\"" + week + "\",\"weeks\":2}").andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("fromWeek")).andReturn();
			assertNoInternalDetail(result);
		}

		assertThat(BudgetScenario.budgetsFingerprint(owner, s.owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void invalidAmountsAreRefusedNamingTheAmountField() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		for (String amount : new String[] { "-1", "-100000", "1000000000001", "9223372036854775807",
				"99999999999999999999", "0.5", "1e3", "10.0", "true", "[]", "{}", "null" }) {
			MvcResult result = define(mvc, s.owner(), "{\"week\":\"2026-W45\",\"amountCents\":" + amount + "}")
					.andExpect(status().isBadRequest()).andReturn();
			assertNoInternalDetail(result);
		}
		define(mvc, s.owner(), "{\"week\":\"2026-W45\",\"amountCents\":1000000000001}")
				.andExpect(jsonPath("$.errors[0].field").value("amountCents"));
		assertThat(fixtures().countWeeklyBudgets(s.owner().workspaceId())).isZero();
	}

	@Test
	void aMalformedCategoryIdIsRefusedAndNothingIsWritten() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		for (String category : new String[] { "\"abc\"", "123", "true", "[]", "\"\"" }) {
			MvcResult result = define(mvc, s.owner(),
					"{\"week\":\"2026-W45\",\"categoryId\":" + category + ",\"amountCents\":100}")
					.andExpect(status().isBadRequest()).andReturn();
			assertNoInternalDetail(result);
		}
		assertThat(fixtures().countWeeklyBudgets(s.owner().workspaceId())).isZero();
	}

	@Test
	void emptyOrBrokenBodiesAndWrongContentTypesAreRefusedWithoutLeakingDetails() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());
		String url = base(s.owner().workspaceId());

		for (String broken : new String[] { "", "{", "[]", "null", "\"texto\"", "{\"week\":" }) {
			MvcResult result = mvc.perform(put(url).cookie(session(s.owner().token()))
					.contentType(MediaType.APPLICATION_JSON).content(broken)).andExpect(status().isBadRequest()).andReturn();
			assertNoInternalDetail(result);
			result = mvc.perform(post(url + "/copy").cookie(session(s.owner().token()))
					.contentType(MediaType.APPLICATION_JSON).content(broken)).andExpect(status().isBadRequest())
					.andReturn();
			assertNoInternalDetail(result);
		}
		MvcResult wrongType = mvc.perform(put(url).cookie(session(s.owner().token()))
				.contentType(MediaType.TEXT_PLAIN).content(body("2026-W45", null, 100)))
				.andExpect(status().isUnsupportedMediaType()).andReturn();
		assertNoInternalDetail(wrongType);
		assertThat(fixtures().countWeeklyBudgets(s.owner().workspaceId())).isZero();
	}

	@Test
	void extraFieldsAreRefusedOnEveryWriteRoute() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID id = fixtures.insertWeeklyBudget(s.owner().workspaceId(), BudgetScenario.W45, null, 100,
				s.owner().userId());
		String before = BudgetScenario.budgetsFingerprint(owner, s.owner().workspaceId());
		UUID w = s.owner().workspaceId();

		for (String extra : new String[] { "\"workspaceId\":\"" + UUID.randomUUID() + "\"",
				"\"createdBy\":\"" + UUID.randomUUID() + "\"", "\"createdAt\":\"2020-01-01T00:00:00Z\"",
				"\"updatedAt\":\"2020-01-01T00:00:00Z\"", "\"id\":\"" + UUID.randomUUID() + "\"", "\"kind\":\"INCOME\"",
				"\"weekStart\":\"2026-11-03\"" }) {
			define(mvc, s.owner(), "{\"week\":\"2026-W46\",\"amountCents\":100," + extra + "}")
					.andExpect(status().isBadRequest());
			BudgetScenario.update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":100," + extra + "}")
					.andExpect(status().isBadRequest());
			BudgetScenario.copy(mvc, w, s.owner(), "{\"fromWeek\":\"2026-W45\",\"weeks\":2," + extra + "}")
					.andExpect(status().isBadRequest());
		}

		assertThat(BudgetScenario.budgetsFingerprint(owner, w)).isEqualTo(before);
	}

	@Test
	void theCopyCountHasExactBounds() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);
		UUID w = s.owner().workspaceId();

		for (String weeks : new String[] { "0", "-1", "53", "100", "2.5", "\"3\"x", "null", "true", "[]" }) {
			BudgetScenario.copy(mvc, w, s.owner(), "{\"fromWeek\":\"2026-W45\",\"weeks\":" + weeks + "}")
					.andExpect(status().isBadRequest());
		}
		assertThat(fixtures.countWeeklyBudgets(w)).isEqualTo(4);
		BudgetScenario.copy(mvc, w, s.owner(), "{\"fromWeek\":\"2026-W45\",\"weeks\":1}").andExpect(status().isOk());
		BudgetScenario.copy(mvc, w, s.owner(), "{\"fromWeek\":\"2026-W45\",\"weeks\":52}").andExpect(status().isOk());
	}

}
