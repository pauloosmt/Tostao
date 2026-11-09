package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.month;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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

/** História 2: o acompanhamento do mês contra os limites (gasto, restante, percentual e fora dos limites). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MonthlyBudgetTrackingTest {

	private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

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

	@Test
	void theQuickstartNumbersAreTheOnesInTheMonth() throws Exception {
		MonthlyBudgetScenario s = seeded();

		month(mvc, s.owner(), "month=2026-11").andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(jsonPath("$.month.id").value("2026-11"))
				.andExpect(jsonPath("$.month.startDate").value("2026-11-01"))
				.andExpect(jsonPath("$.month.endDate").value("2026-11-30"))
				.andExpect(jsonPath("$.totalSpentCents").value(104_000))
				.andExpect(jsonPath("$.spentOutsideCategoryLimitsCents").value(8_000))
				.andExpect(jsonPath("$.budgets[*].categoryName").value(org.hamcrest.Matchers.contains("Transporte",
						"Alimentação", "Mercado", null)))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Alimentação')].spentCents").value(60_000))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Mercado')].spentCents").value(20_000));
	}

	@Test
	void receiptsTransfersAndDeletedTransactionsNeverCountAndTheNeighbouringDaysAreOut() throws Exception {
		MonthlyBudgetScenario s = seeded();

		// o cenário tem receita de 500000, transferência de 30000, despesa excluída de 7777 e despesas em
		// 31/10 (99999) e 01/12 (4000): nenhuma entra. 01/11 (40000) e 30/11 (8000) entram
		month(mvc, s.owner(), "month=2026-11").andExpect(jsonPath("$.totalSpentCents").value(104_000));
		month(mvc, s.owner(), "month=2026-10").andExpect(jsonPath("$.totalSpentCents").value(99_999));
		month(mvc, s.owner(), "month=2026-12").andExpect(jsonPath("$.totalSpentCents").value(4_000));
	}

	@Test
	void anUncategorizedExpenseCountsInTheGeneralLimitAndOutsideTheCategoryLimits() throws Exception {
		MonthlyBudgetScenario s = seeded();
		fixtures().insertLedgerTransaction(s.owner().workspaceId(), s.checking(), s.owner().userId(), null, "EXPENSE",
				500, LocalDate.of(2026, 11, 12), "Sem categoria", false);

		month(mvc, s.owner(), "month=2026-11").andExpect(jsonPath("$.totalSpentCents").value(104_500))
				.andExpect(jsonPath("$.spentOutsideCategoryLimitsCents").value(8_500))
				.andExpect(jsonPath("$.budgets[?(@.categoryName==null)].spentCents").value(104_500));
	}

	@Test
	void theMonthsOfDifferentSizesAndTheYearTurnAreCountedFromTheFirstToTheLastDay() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID user = s.owner().userId();
		for (String month : new String[] { "2027-02-01", "2028-02-01", "2026-12-01", "2027-01-01" }) {
			fixtures().insertMonthlyBudget(w, LocalDate.parse(month), null, 100_000, user);
		}
		// fevereiro de 2027 tem 28 dias; o de 2028 (bissexto) tem 29
		spend(s, 100, "2027-02-28");
		spend(s, 7, "2027-03-01");
		spend(s, 100, "2028-02-29");
		spend(s, 7, "2028-03-01");
		// virada de ano
		spend(s, 300, "2026-12-31");
		spend(s, 700, "2027-01-01");

		month(mvc, s.owner(), "month=2027-02").andExpect(jsonPath("$.totalSpentCents").value(100))
				.andExpect(jsonPath("$.month.endDate").value("2027-02-28"));
		month(mvc, s.owner(), "month=2028-02").andExpect(jsonPath("$.totalSpentCents").value(100))
				.andExpect(jsonPath("$.month.endDate").value("2028-02-29"));
		// dezembro de 2026 também tem a despesa de 4000 do cenário em 01/12
		month(mvc, s.owner(), "month=2026-12").andExpect(jsonPath("$.totalSpentCents").value(4_300));
		month(mvc, s.owner(), "month=2027-01").andExpect(jsonPath("$.totalSpentCents").value(700));
	}

	private void spend(MonthlyBudgetScenario s, long cents, String date) {
		fixtures().insertLedgerTransaction(s.owner().workspaceId(), s.checking(), s.owner().userId(), s.food(),
				"EXPENSE", cents, LocalDate.parse(date), "Gasto " + date, false);
	}

	@Test
	void aFutureDateInsideTheMonthCounts() throws Exception {
		MonthlyBudgetScenario s = seeded();
		spend(s, 900, "2026-11-29");

		month(mvc, s.owner(), "month=2026-11").andExpect(jsonPath("$.totalSpentCents").value(104_900));
	}

	@Test
	void archivedAccountsAndCategoriesStillCountAndAnArchivedCategoryKeepsItsLimit() throws Exception {
		MonthlyBudgetScenario s = seeded();
		owner.client().sql("update categories set archived = true where id = :id").param("id", s.food()).update();
		owner.client().sql("update accounts set archived = true where id = :id").param("id", s.checking()).update();

		month(mvc, s.owner(), "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.totalSpentCents").value(104_000))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Alimentação')].categoryArchived").value(true))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Alimentação')].spentCents").value(60_000));
	}

	@Test
	void aMonthWithoutLimitsGivesAnEmptyListAndTheTotalSpent() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());

		month(mvc, s.owner(), "month=2026-11").andExpect(status().isOk()).andExpect(jsonPath("$.budgets.length()").value(0))
				.andExpect(jsonPath("$.totalSpentCents").value(104_000))
				.andExpect(jsonPath("$.spentOutsideCategoryLimitsCents").value(104_000));
	}

	@Test
	void withoutAMonthTheCurrentOneIsUsedAndAnEmptyOrBadOneIsRejected() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());

		// o teste não fixa o relógio: aceita o mês de antes ou de depois da chamada (virada de mês)
		YearMonth before = YearMonth.now(BRASILIA);
		String id = JsonPath.read(month(mvc, s.owner(), "").andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString(), "$.month.id");
		YearMonth after = YearMonth.now(BRASILIA);
		assertThat(id).isIn(before.toString(), after.toString());

		month(mvc, s.owner(), "month=").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("month"));
		month(mvc, s.owner(), "month=2026-13").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("month"));
	}

	@Test
	void anotherWorkspacesSpendingNeverEntersTheSums() throws Exception {
		MonthlyBudgetScenario a = seeded();
		MonthlyBudgetScenario b = MonthlyBudgetScenario.create(fixtures());
		fixtures().insertLedgerTransaction(b.owner().workspaceId(), b.checking(), b.owner().userId(), b.food(),
				"EXPENSE", 999_999, LocalDate.of(2026, 11, 10), "Só de B", false);

		month(mvc, a.owner(), "month=2026-11").andExpect(jsonPath("$.totalSpentCents").value(104_000));
		month(mvc, b.owner(), "month=2026-11").andExpect(jsonPath("$.totalSpentCents").value(104_000 + 999_999));
	}

	@Test
	void weeklyLimitsOfTheSameCategoryNeverAppearInTheMonthlyTracking() throws Exception {
		MonthlyBudgetScenario s = seeded();
		fixtures().insertWeeklyBudget(s.owner().workspaceId(), LocalDate.of(2026, 11, 2), s.leisure(), 5_000,
				s.owner().userId());

		month(mvc, s.owner(), "month=2026-11").andExpect(jsonPath("$.budgets.length()").value(4))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Lazer')]").isEmpty());
	}

}
