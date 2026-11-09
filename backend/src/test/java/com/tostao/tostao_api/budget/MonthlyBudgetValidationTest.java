package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
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

/** Validação de toda entrada do orçamento mensal: campo certo no erro, nada gravado e nada interno vazando. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MonthlyBudgetValidationTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	@Test
	void badMonthsInTheThreeEntryPointsNameTheRightField() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		String before = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w);

		for (String bad : new String[] { "2026-1", "202611", "2026/11", "2026-13", "2026-00", "1999-12", "2101-01",
				"2026-11-01", "2026-W45", "", " 2026-11", "2026-11 " }) {
			MonthlyBudgetScenario.month(mvc, s.owner(), "month=" + java.net.URLEncoder.encode(bad, "UTF-8"))
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("month"));
			MonthlyBudgetScenario.define(mvc, s.owner(), "{\"month\":\"" + bad + "\",\"amountCents\":1}")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("month"));
			MonthlyBudgetScenario.copy(mvc, w, s.owner(), "{\"fromMonth\":\"" + bad + "\",\"months\":1}")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("fromMonth"));
		}
		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w)).isEqualTo(before);
	}

	@Test
	void badAmountsOnDefineAndUpdateNameAmountCents() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID id = fixtures().insertMonthlyBudget(w, MonthlyBudgetScenario.NOV, s.food(), 100, s.owner().userId());
		String before = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w);

		for (String bad : new String[] { "-1", "1000000000001", "0.5", "true", "null", "[]" }) {
			MonthlyBudgetScenario.define(mvc, s.owner(), "{\"month\":\"2026-12\",\"amountCents\":" + bad + "}")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("amountCents"));
			MonthlyBudgetScenario.update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":" + bad + "}")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("amountCents"));
		}
		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w)).isEqualTo(before);
	}

	@Test
	void aMalformedCategoryIdIsRejectedNamingTheField() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());

		for (String bad : new String[] { "\"abc\"", "123", "\"\"", "\" \"", "true" }) {
			MonthlyBudgetScenario.define(mvc, s.owner(),
					"{\"month\":\"2026-12\",\"categoryId\":" + bad + ",\"amountCents\":1}")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("categoryId"));
		}
		assertThat(fixtures().countMonthlyBudgets(s.owner().workspaceId())).isZero();
	}

	@Test
	void anEmptyNotJsonOrWrongContentTypeBodyIsRefusedWithoutLeakingAnything() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		String base = MonthlyBudgetScenario.base(s.owner().workspaceId());

		MvcResult empty = mvc.perform(put(base).cookie(session(s.owner().token())).contentType(MediaType.APPLICATION_JSON)
				.content("")).andExpect(status().isBadRequest()).andReturn();
		MvcResult notJson = mvc.perform(put(base).cookie(session(s.owner().token())).contentType(MediaType.APPLICATION_JSON)
				.content("{isto nao e json")).andExpect(status().isBadRequest()).andReturn();
		MvcResult wrongType = mvc.perform(put(base).cookie(session(s.owner().token())).contentType(MediaType.TEXT_PLAIN)
				.content("{\"month\":\"2026-11\",\"amountCents\":1}")).andExpect(status().isUnsupportedMediaType())
				.andReturn();
		MvcResult copyEmpty = mvc.perform(post(base + "/copy").cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON).content("")).andExpect(status().isBadRequest()).andReturn();

		for (MvcResult result : new MvcResult[] { empty, notJson, wrongType, copyEmpty }) {
			String body = result.getResponse().getContentAsString();
			assertThat(body).doesNotContain("SQLException").doesNotContain("monthly_budgets").doesNotContain("stack")
					.doesNotContain("Exception").doesNotContain("com.tostao");
		}
		assertThat(fixtures().countMonthlyBudgets(s.owner().workspaceId())).isZero();
	}

	@Test
	void fieldsOutsideTheAllowedListAreRejectedOnEveryWrite() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID id = fixtures().insertMonthlyBudget(w, MonthlyBudgetScenario.NOV, s.food(), 100, s.owner().userId());
		String before = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w);

		for (String extra : new String[] { "\"workspaceId\":\"" + UUID.randomUUID() + "\"",
				"\"createdBy\":\"" + UUID.randomUUID() + "\"", "\"createdAt\":\"2026-01-01T00:00:00Z\"",
				"\"updatedAt\":\"2026-01-01T00:00:00Z\"", "\"id\":\"" + UUID.randomUUID() + "\"", "\"kind\":\"INCOME\"" }) {
			MonthlyBudgetScenario.define(mvc, s.owner(), "{\"month\":\"2026-12\",\"amountCents\":1," + extra + "}")
					.andExpect(status().isBadRequest());
			MonthlyBudgetScenario.update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":1," + extra + "}")
					.andExpect(status().isBadRequest());
			MonthlyBudgetScenario.copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":1," + extra + "}")
					.andExpect(status().isBadRequest());
		}
		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w)).isEqualTo(before);
	}

	@Test
	void theExactLimitsOfTheCopyMonthsAreEnforced() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();

		for (String months : new String[] { "0", "25", "-1", "2.5", "null", "true" }) {
			MonthlyBudgetScenario.copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":" + months + "}")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("months"));
		}
		for (String months : new String[] { "1", "24" }) {
			MonthlyBudgetScenario.copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":" + months + "}")
					.andExpect(status().isOk());
		}
	}

}
