package com.tostao.tostao_api.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

/**
 * SC-003 e FR-009: o mesmo gasto e o mesmo limite dão o mesmo resultado no orçamento semanal e no mensal. O
 * gasto é numa quarta-feira (2027-04-07, semana 2027-W14, de 05/04 a 11/04), toda dentro do mês 2027-04.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BudgetParityTest {

	private static final LocalDate MONDAY = LocalDate.of(2027, 4, 5);
	private static final LocalDate FIRST = LocalDate.of(2027, 4, 1);
	private static final LocalDate WEDNESDAY = LocalDate.of(2027, 4, 7);

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	@Test
	void theSameSpendingAndLimitGiveTheSameNumbersInBothBudgets() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID account = fixtures().insertAccount(w, "Corrente", "CHECKING", 0, false);
		// (gasto, limite): 79,99%, 80%, 100%, 100,01%, zero sem gasto, zero com gasto, arredondamento enganoso
		long[][] pairs = { { 7_999, 10_000 }, { 8_000, 10_000 }, { 10_000, 10_000 }, { 10_001, 10_000 }, { 0, 0 },
				{ 1, 0 }, { 79_999, 100_000 } };
		for (int i = 0; i < pairs.length; i++) {
			UUID category = fixtures().insertCategory(w, null, "Par %d".formatted(i), "EXPENSE", false);
			if (pairs[i][0] > 0) {
				fixtures().insertLedgerTransaction(w, account, fx.userId(), category, "EXPENSE", pairs[i][0], WEDNESDAY,
						"Gasto " + i, false);
			}
			fixtures().insertWeeklyBudget(w, MONDAY, category, pairs[i][1], fx.userId());
			fixtures().insertMonthlyBudget(w, FIRST, category, pairs[i][1], fx.userId());
		}

		String weekly = body(BudgetScenario.week(mvc, fx, "week=2027-W14").andExpect(status().isOk()));
		String monthly = body(MonthlyBudgetScenario.month(mvc, fx, "month=2027-04").andExpect(status().isOk()));

		assertThat((List<?>) JsonPath.read(weekly, "$.budgets")).hasSize(pairs.length);
		assertThat((List<?>) JsonPath.read(monthly, "$.budgets")).hasSize(pairs.length);
		for (int i = 0; i < pairs.length; i++) {
			String path = "$.budgets[?(@.categoryName=='Par %d')]".formatted(i);
			Map<String, Object> fromWeek = ((List<Map<String, Object>>) JsonPath.read(weekly, path)).get(0);
			Map<String, Object> fromMonth = ((List<Map<String, Object>>) JsonPath.read(monthly, path)).get(0);
			for (String field : new String[] { "amountCents", "spentCents", "remainingCents", "percentUsed", "status",
					"categoryArchived" }) {
				assertThat(fromMonth.get(field)).as("par %d, campo %s".formatted(i, field)).isEqualTo(fromWeek.get(field));
			}
		}
		// e a ordem relativa dos itens é a mesma
		assertThat((List<?>) JsonPath.read(monthly, "$.budgets[*].categoryName"))
				.isEqualTo((List<?>) JsonPath.read(weekly, "$.budgets[*].categoryName"));
		// os totais também: o mês e a semana têm o mesmo gasto, porque toda a despesa cai nos dois
		assertThat(((Number) JsonPath.read(monthly, "$.totalSpentCents")).longValue())
				.isEqualTo(((Number) JsonPath.read(weekly, "$.totalSpentCents")).longValue());
		assertThat(((Number) JsonPath.read(monthly, "$.spentOutsideCategoryLimitsCents")).longValue())
				.isEqualTo(((Number) JsonPath.read(weekly, "$.spentOutsideCategoryLimitsCents")).longValue());
	}

}
