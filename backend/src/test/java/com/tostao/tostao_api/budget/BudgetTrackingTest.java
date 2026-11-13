package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.BudgetScenario.week;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
import com.tostao.tostao_api.dashboard.Period;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** História 2: acompanhar a semana contra os limites (gasto, restante, percentual, estado e totais). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BudgetTrackingTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private String track(Fixture who, String query) throws Exception {
		return week(mvc, who, query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private static long number(String json, String path) {
		return ((Number) JsonPath.read(json, path)).longValue();
	}

	private static long first(String json, String path) {
		return JsonPath.<List<Number>>read(json, path).get(0).longValue();
	}

	private static List<String> names(String json) {
		return JsonPath.read(json, "$.budgets[*].categoryName");
	}

	@Test
	void quickstartWeekShowsSpentRemainingPercentAndStatusOfEveryLimit() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);

		week(mvc, s.owner(), "week=2026-W45").andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(jsonPath("$.week.id").value("2026-W45"))
				.andExpect(jsonPath("$.week.startDate").value("2026-11-02"))
				.andExpect(jsonPath("$.week.endDate").value("2026-11-08"))
				.andExpect(jsonPath("$.totalSpentCents").value(26_000))
				.andExpect(jsonPath("$.spentOutsideCategoryLimitsCents").value(2_000))
				.andExpect(jsonPath("$.budgets.length()").value(4))
				// Transporte: estourado
				.andExpect(jsonPath("$.budgets[0].categoryName").value("Transporte"))
				.andExpect(jsonPath("$.budgets[0].amountCents").value(8_000))
				.andExpect(jsonPath("$.budgets[0].spentCents").value(9_000))
				.andExpect(jsonPath("$.budgets[0].remainingCents").value(-1_000))
				.andExpect(jsonPath("$.budgets[0].percentUsed").value(112.5))
				.andExpect(jsonPath("$.budgets[0].status").value("EXCEEDED"))
				// Alimentação: direto 10000 + Mercado 5000
				.andExpect(jsonPath("$.budgets[1].categoryName").value("Alimentação"))
				.andExpect(jsonPath("$.budgets[1].spentCents").value(15_000))
				.andExpect(jsonPath("$.budgets[1].remainingCents").value(1_000))
				.andExpect(jsonPath("$.budgets[1].percentUsed").value(93.75))
				.andExpect(jsonPath("$.budgets[1].status").value("WARNING"))
				// Mercado: só os gastos dela
				.andExpect(jsonPath("$.budgets[2].categoryName").value("Mercado"))
				.andExpect(jsonPath("$.budgets[2].spentCents").value(5_000))
				.andExpect(jsonPath("$.budgets[2].remainingCents").value(0))
				.andExpect(jsonPath("$.budgets[2].percentUsed").value(100.0))
				.andExpect(jsonPath("$.budgets[2].status").value("WARNING"))
				// geral
				.andExpect(jsonPath("$.budgets[3].categoryId").value((Object) null))
				.andExpect(jsonPath("$.budgets[3].categoryName").value((Object) null))
				.andExpect(jsonPath("$.budgets[3].amountCents").value(100_000))
				.andExpect(jsonPath("$.budgets[3].spentCents").value(22_000))
				.andExpect(jsonPath("$.budgets[3].remainingCents").value(78_000))
				.andExpect(jsonPath("$.budgets[3].percentUsed").value(22.0))
				.andExpect(jsonPath("$.budgets[3].status").value("WITHIN"));
	}

	@Test
	void eachItemCarriesWhoCreatedTheLimitAndWhen() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);

		String json = track(s.owner(), "week=2026-W45");

		for (int i = 0; i < 4; i++) {
			assertThat((String) JsonPath.read(json, "$.budgets[" + i + "].createdBy"))
					.isEqualTo(s.owner().userId().toString());
			assertThat(Instant.parse(JsonPath.read(json, "$.budgets[" + i + "].createdAt"))).isNotNull();
			assertThat(Instant.parse(JsonPath.read(json, "$.budgets[" + i + "].updatedAt"))).isNotNull();
			assertThat((String) JsonPath.read(json, "$.budgets[" + i + "].id")).isNotBlank();
		}
	}

	@Test
	void anAggregateLimitIsNeverSummedWithTheOthers() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);

		String json = track(s.owner(), "week=2026-W45");

		// o mesmo gasto de Mercado conta no limite de Mercado, no de Alimentação e no geral, sem duplicar o total
		assertThat(number(json, "$.totalSpentCents")).isEqualTo(26_000);
		assertThat(JsonPath.<java.util.Map<String, Object>>read(json, "$")).doesNotContainKeys("totalBudgetCents",
				"totalLimitCents");
	}

	@Test
	void incomeTransfersAndDeletedExpensesNeverCountAndNeighbourWeeksAreOutside() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);
		UUID w = s.owner().workspaceId();
		// os extremos da semana contam: segunda 02/11 e domingo 08/11
		fixtures.insertLedgerTransaction(w, s.checking(), s.owner().userId(), s.leisure(), "EXPENSE", 100,
				LocalDate.of(2026, 11, 2), "segunda", false);
		fixtures.insertLedgerTransaction(w, s.checking(), s.owner().userId(), s.leisure(), "EXPENSE", 200,
				LocalDate.of(2026, 11, 8), "domingo", false);

		String json = track(s.owner(), "week=2026-W45");

		// 26000 do cenário (receita, transferência, excluído, 01/11 e 09/11 de fora) + 100 + 200
		assertThat(number(json, "$.totalSpentCents")).isEqualTo(26_300);
		assertThat(number(json, "$.spentOutsideCategoryLimitsCents")).isEqualTo(2_300);
	}

	@Test
	void theGeneralLimitCountsUncategorizedExpensesAndTheyAreOutsideTheCategoryLimits() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);
		UUID w = s.owner().workspaceId();
		fixtures.insertLedgerTransaction(w, s.checking(), s.owner().userId(), null, "EXPENSE", 700,
				LocalDate.of(2026, 11, 4), "importada sem categoria", false);

		String json = track(s.owner(), "week=2026-W45");

		assertThat(number(json, "$.totalSpentCents")).isEqualTo(26_700);
		assertThat(number(json, "$.spentOutsideCategoryLimitsCents")).isEqualTo(2_700);
		assertThat(first(json, "$.budgets[?(@.categoryName == null)].spentCents")).isEqualTo(22_700);
		// os limites de categoria não enxergam a despesa sem categoria
		assertThat(first(json, "$.budgets[?(@.categoryName == 'Alimentação')].spentCents")).isEqualTo(15_000);
	}

	@Test
	void aWeekThatCrossesTheYearCountsAllSevenDays() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		fixtures.insertWeeklyBudget(w, LocalDate.of(2026, 12, 28), null, 10_000, s.owner().userId());
		for (String[] row : new String[][] { { "2026-12-27", "1" }, { "2026-12-28", "10" }, { "2026-12-31", "100" },
				{ "2027-01-01", "1000" }, { "2027-01-03", "10000" }, { "2027-01-04", "100000" } }) {
			fixtures.insertLedgerTransaction(w, s.checking(), s.owner().userId(), s.food(), "EXPENSE",
					Long.parseLong(row[1]), LocalDate.parse(row[0]), "dia " + row[0], false);
		}

		String json = track(s.owner(), "week=2026-W53");

		assertThat(number(json, "$.totalSpentCents")).isEqualTo(11_110);
		assertThat(number(json, "$.budgets[0].spentCents")).isEqualTo(11_110);
		assertThat(JsonPath.<String>read(json, "$.week.startDate")).isEqualTo("2026-12-28");
		assertThat(JsonPath.<String>read(json, "$.week.endDate")).isEqualTo("2027-01-03");
	}

	@Test
	void anExpenseDatedLaterInTheWeekStillCounts() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		fixtures.insertWeeklyBudget(w, LocalDate.of(2027, 3, 8), s.leisure(), 5_000, s.owner().userId());
		fixtures.insertLedgerTransaction(w, s.checking(), s.owner().userId(), s.leisure(), "EXPENSE", 1_200,
				LocalDate.of(2027, 3, 12), "futura", false);

		String json = track(s.owner(), "week=2027-W10");

		assertThat(number(json, "$.budgets[0].spentCents")).isEqualTo(1_200);
	}

	@Test
	void archivedAccountsAndCategoriesStillCountAndAnArchivedCategoryKeepsItsLimit() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);
		UUID w = s.owner().workspaceId();
		UUID oldAccount = fixtures.insertAccount(w, "Antiga", "CASH", 0, true);
		UUID oldCategory = fixtures.insertCategory(w, null, "Antiga", "EXPENSE", true);
		fixtures.insertLedgerTransaction(w, oldAccount, s.owner().userId(), s.transport(), "EXPENSE", 300,
				LocalDate.of(2026, 11, 5), "em conta arquivada", false);
		fixtures.insertLedgerTransaction(w, s.checking(), s.owner().userId(), oldCategory, "EXPENSE", 400,
				LocalDate.of(2026, 11, 5), "em categoria arquivada", false);
		fixtures.insertWeeklyBudget(w, BudgetScenario.W45, oldCategory, 1_000, s.owner().userId());

		String json = track(s.owner(), "week=2026-W45");

		assertThat(number(json, "$.totalSpentCents")).isEqualTo(26_700);
		assertThat(first(json, "$.budgets[?(@.categoryName == 'Transporte')].spentCents")).isEqualTo(9_300);
		assertThat(first(json, "$.budgets[?(@.categoryName == 'Antiga')].spentCents")).isEqualTo(400);
		assertThat((Boolean) JsonPath.<List<Boolean>>read(json, "$.budgets[?(@.categoryName == 'Antiga')].categoryArchived")
				.get(0)).isTrue();
		assertThat((Boolean) JsonPath.<List<Boolean>>read(json, "$.budgets[?(@.categoryName == 'Transporte')].categoryArchived")
				.get(0)).isFalse();
		// a categoria arquivada tem limite: o gasto dela não é "fora dos limites"
		assertThat(number(json, "$.spentOutsideCategoryLimitsCents")).isEqualTo(2_000);
	}

	@Test
	void aZeroLimitIsWithinWithoutSpendingAndExceededWithAnySpendingWithNoPercentage() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		fixtures.insertWeeklyBudget(w, BudgetScenario.W45, s.leisure(), 0, s.owner().userId());
		fixtures.insertWeeklyBudget(w, BudgetScenario.W45.plusDays(7), s.leisure(), 0, s.owner().userId());

		week(mvc, s.owner(), "week=2026-W45").andExpect(status().isOk())
				.andExpect(jsonPath("$.budgets[0].spentCents").value(2_000))
				.andExpect(jsonPath("$.budgets[0].status").value("EXCEEDED"))
				.andExpect(jsonPath("$.budgets[0].percentUsed").value((Object) null))
				.andExpect(jsonPath("$.budgets[0].remainingCents").value(-2_000));
		week(mvc, s.owner(), "week=2026-W46").andExpect(status().isOk())
				.andExpect(jsonPath("$.budgets[0].spentCents").value(0))
				.andExpect(jsonPath("$.budgets[0].status").value("WITHIN"))
				.andExpect(jsonPath("$.budgets[0].percentUsed").value((Object) null));
	}

	@Test
	void aWeekWithoutLimitsReturnsAnEmptyListAndTheTotalSpent() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		week(mvc, s.owner(), "week=2026-W45").andExpect(status().isOk())
				.andExpect(jsonPath("$.budgets.length()").value(0))
				.andExpect(jsonPath("$.totalSpentCents").value(26_000))
				.andExpect(jsonPath("$.spentOutsideCategoryLimitsCents").value(26_000));
		week(mvc, s.owner(), "week=2026-W10").andExpect(status().isOk())
				.andExpect(jsonPath("$.budgets.length()").value(0))
				.andExpect(jsonPath("$.totalSpentCents").value(0))
				.andExpect(jsonPath("$.spentOutsideCategoryLimitsCents").value(0));
	}

	@Test
	void removingLimitsMovesTheirSpendingOutsideTheCategoryLimits() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);
		UUID w = s.owner().workspaceId();

		owner.client().sql("delete from weekly_budgets where workspace_id = :w and category_id = :c").param("w", w)
				.param("c", s.food()).update();
		// os 10000 lançados direto em Alimentação saem do alcance; os 5000 de Mercado seguem cobertos
		assertThat(number(track(s.owner(), "week=2026-W45"), "$.spentOutsideCategoryLimitsCents")).isEqualTo(12_000);

		owner.client().sql("delete from weekly_budgets where workspace_id = :w and category_id = :c").param("w", w)
				.param("c", s.market()).update();
		assertThat(number(track(s.owner(), "week=2026-W45"), "$.spentOutsideCategoryLimitsCents")).isEqualTo(17_000);
	}

	@Test
	void repeatedQueriesGiveTheSameResponse() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);

		String first = track(s.owner(), "week=2026-W45");

		for (int i = 0; i < 5; i++) {
			assertThat(track(s.owner(), "week=2026-W45")).isEqualTo(first);
		}
		assertThat(names(first)).containsExactly("Transporte", "Alimentação", "Mercado", null);
	}

	@Test
	void twoWorkspacesNeverSeeEachOthersSpendingOrLimits() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario a = BudgetScenario.create(fixtures);
		a.seedQuickstartBudgets(fixtures);
		BudgetScenario b = BudgetScenario.create(fixtures);
		fixtures.insertWeeklyBudget(b.owner().workspaceId(), BudgetScenario.W45, null, 7_000, b.owner().userId());
		fixtures.insertLedgerTransaction(b.owner().workspaceId(), b.checking(), b.owner().userId(), b.food(), "EXPENSE",
				333, LocalDate.of(2026, 11, 3), "só do B", false);

		String forA = track(a.owner(), "week=2026-W45");
		String forB = track(b.owner(), "week=2026-W45");

		assertThat(number(forA, "$.totalSpentCents")).isEqualTo(26_000);
		assertThat(number(forB, "$.totalSpentCents")).isEqualTo(26_333);
		assertThat(JsonPath.<List<?>>read(forA, "$.budgets")).hasSize(4);
		assertThat(JsonPath.<List<?>>read(forB, "$.budgets")).hasSize(1);
		assertThat(number(forB, "$.budgets[0].amountCents")).isEqualTo(7_000);
	}

	@Test
	void withoutTheWeekParameterTheCurrentWeekIsUsed() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		String json = track(s.owner(), "");

		assertThat(JsonPath.<String>read(json, "$.week.id")).isIn(Period.resolveWeek(null, Instant.now()).id(),
				Period.resolveWeek(null, Instant.now().minusSeconds(5)).id());
	}

	@Test
	void largeAmountsStayExact() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		fixtures.insertWeeklyBudget(w, BudgetScenario.W45.plusDays(14), null, 1_000_000_000_000L, s.owner().userId());
		for (int i = 0; i < 5; i++) {
			fixtures.insertLedgerTransaction(w, s.checking(), s.owner().userId(), s.food(), "EXPENSE",
					1_000_000_000_000L, LocalDate.of(2026, 11, 16), "enorme " + i, false);
		}
		fixtures.insertLedgerTransaction(w, s.checking(), s.owner().userId(), s.food(), "EXPENSE", 10, LocalDate.of(2026, 11, 17),
				"R$ 0,10", false);
		fixtures.insertLedgerTransaction(w, s.checking(), s.owner().userId(), s.food(), "EXPENSE", 20, LocalDate.of(2026, 11, 17),
				"R$ 0,20", false);

		week(mvc, s.owner(), "week=2026-W47").andExpect(status().isOk())
				.andExpect(jsonPath("$.totalSpentCents").value(5_000_000_000_030L))
				.andExpect(jsonPath("$.budgets[0].spentCents").value(5_000_000_000_030L))
				.andExpect(jsonPath("$.budgets[0].remainingCents").value(-4_000_000_000_030L))
				.andExpect(jsonPath("$.budgets[0].percentUsed").value(500.0))
				.andExpect(jsonPath("$.budgets[0].status").value("EXCEEDED"));
	}

}
