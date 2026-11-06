package com.tostao.tostao_api.dashboard;

import static com.tostao.tostao_api.dashboard.DashboardScenario.dashboard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** US4: o mesmo painel para uma semana ISO (segunda a domingo), para acompanhar semana a semana. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WeeklyDashboardTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private String body(Fixture who, String query) throws Exception {
		return dashboard(mvc, who, query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private static long number(String json, String path) {
		return ((Number) JsonPath.read(json, path)).longValue();
	}

	private static long sum(String json, String path) {
		return JsonPath.<List<Number>>read(json, path).stream().mapToLong(Number::longValue).sum();
	}

	private record World(WorkspaceFixtures fx, Fixture owner, UUID account, UUID expense, UUID income) {

		void ledger(UUID category, String type, long cents, LocalDate date) {
			fx.insertLedgerTransaction(owner.workspaceId(), account, owner.userId(), category, type, cents, date, "T",
					false);
		}

	}

	private World world() {
		WorkspaceFixtures fx = fixtures();
		Fixture owner = fx.workspaceWithOwner();
		UUID account = fx.insertAccount(owner.workspaceId(), "Corrente", "CHECKING", 0, false);
		return new World(fx, owner, account, fx.insertCategory(owner.workspaceId(), null, "Mercado", "EXPENSE", false),
				fx.insertCategory(owner.workspaceId(), null, "Salário", "INCOME", false));
	}

	@Test
	void theScenarioWeekHasSevenDaysAndTheExpectedTotals() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		String json = body(s.owner(), "week=2026-W45");

		assertThat(JsonPath.<String>read(json, "$.period.type")).isEqualTo("WEEK");
		assertThat(JsonPath.<String>read(json, "$.period.id")).isEqualTo("2026-W45");
		assertThat(JsonPath.<String>read(json, "$.period.startDate")).isEqualTo("2026-11-02");
		assertThat(JsonPath.<String>read(json, "$.period.endDate")).isEqualTo("2026-11-08");
		assertThat(number(json, "$.totals.incomeCents")).isEqualTo(250_000);
		assertThat(number(json, "$.totals.expenseCents")).isEqualTo(15_000);
		assertThat(number(json, "$.totals.resultCents")).isEqualTo(235_000);
		assertThat(JsonPath.<String>read(json, "$.expenseByCategory[0].name")).isEqualTo("Alimentação");
		assertThat(number(json, "$.expenseByCategory[0].totalCents")).isEqualTo(15_000);
		assertThat(number(json, "$.expenseByCategory[0].children[0].totalCents")).isEqualTo(5_000);
	}

	@Test
	void theDailyEvolutionHasSevenDaysFromMondayToSundayAndAddsUpToTheTotals() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		String json = body(s.owner(), "week=2026-W45");

		assertThat(JsonPath.<List<String>>read(json, "$.daily[*].date")).containsExactly("2026-11-02", "2026-11-03",
				"2026-11-04", "2026-11-05", "2026-11-06", "2026-11-07", "2026-11-08");
		assertThat(number(json, "$.daily[0].expenseCents")).isEqualTo(10_000);
		assertThat(number(json, "$.daily[1].expenseCents")).isEqualTo(5_000);
		assertThat(number(json, "$.daily[3].incomeCents")).isEqualTo(250_000);
		assertThat(sum(json, "$.daily[*].incomeCents")).isEqualTo(number(json, "$.totals.incomeCents"));
		assertThat(sum(json, "$.daily[*].expenseCents")).isEqualTo(number(json, "$.totals.expenseCents"));
	}

	@Test
	void theComparisonIsWithThePreviousWeek() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		String json = body(s.owner(), "week=2026-W45");

		assertThat(JsonPath.<String>read(json, "$.comparison.previousPeriod.id")).isEqualTo("2026-W44");
		assertThat(JsonPath.<String>read(json, "$.comparison.previousPeriod.type")).isEqualTo("WEEK");
		assertThat(JsonPath.<String>read(json, "$.comparison.previousPeriod.startDate")).isEqualTo("2026-10-26");
		assertThat(JsonPath.<String>read(json, "$.comparison.previousPeriod.endDate")).isEqualTo("2026-11-01");
		assertThat(number(json, "$.comparison.expense.previousCents")).isEqualTo(20_000);
		assertThat(number(json, "$.comparison.expense.changeCents")).isEqualTo(-5_000);
		assertThat(JsonPath.<Double>read(json, "$.comparison.expense.changePercent")).isEqualTo(-25.00);
		assertThat(JsonPath.<Object>read(json, "$.comparison.income.changePercent")).isNull();
	}

	@Test
	void mondayAndSundayCountButTheNeighboringDaysDoNot() throws Exception {
		World w = world();
		w.ledger(w.expense(), "EXPENSE", 1, LocalDate.of(2026, 11, 2));
		w.ledger(w.expense(), "EXPENSE", 10, LocalDate.of(2026, 11, 8));
		w.ledger(w.expense(), "EXPENSE", 100, LocalDate.of(2026, 11, 1));
		w.ledger(w.expense(), "EXPENSE", 1_000, LocalDate.of(2026, 11, 9));

		String json = body(w.owner(), "week=2026-W45");

		assertThat(number(json, "$.totals.expenseCents")).isEqualTo(11);
		assertThat(number(json, "$.comparison.expense.previousCents")).isEqualTo(100);
	}

	@Test
	void aWeekCrossingTheMonthBoundaryCountsAllSevenDays() throws Exception {
		World w = world();
		for (int day = 26; day <= 31; day++) {
			w.ledger(w.expense(), "EXPENSE", 100, LocalDate.of(2026, 10, day));
		}
		w.ledger(w.expense(), "EXPENSE", 100, LocalDate.of(2026, 11, 1));

		String json = body(w.owner(), "week=2026-W44");

		assertThat(number(json, "$.totals.expenseCents")).isEqualTo(700);
		assertThat(JsonPath.<List<?>>read(json, "$.daily")).hasSize(7);
		assertThat(JsonPath.<String>read(json, "$.daily[0].date")).isEqualTo("2026-10-26");
		assertThat(JsonPath.<String>read(json, "$.daily[6].date")).isEqualTo("2026-11-01");
	}

	@Test
	void aWeekCrossingTheYearBoundaryCountsAllSevenDays() throws Exception {
		World w = world();
		w.ledger(w.expense(), "EXPENSE", 100, LocalDate.of(2024, 12, 30));
		w.ledger(w.expense(), "EXPENSE", 200, LocalDate.of(2025, 1, 1));
		w.ledger(w.expense(), "EXPENSE", 400, LocalDate.of(2025, 1, 5));
		w.ledger(w.expense(), "EXPENSE", 800, LocalDate.of(2025, 1, 6));

		String json = body(w.owner(), "week=2025-W01");

		assertThat(JsonPath.<String>read(json, "$.period.startDate")).isEqualTo("2024-12-30");
		assertThat(JsonPath.<String>read(json, "$.period.endDate")).isEqualTo("2025-01-05");
		assertThat(number(json, "$.totals.expenseCents")).isEqualTo(700);
	}

	@Test
	void week53IsAcceptedInAYearThatHasIt() throws Exception {
		World w = world();
		w.ledger(w.expense(), "EXPENSE", 300, LocalDate.of(2027, 1, 3));

		String json = body(w.owner(), "week=2026-W53");

		assertThat(JsonPath.<String>read(json, "$.period.startDate")).isEqualTo("2026-12-28");
		assertThat(JsonPath.<String>read(json, "$.period.endDate")).isEqualTo("2027-01-03");
		assertThat(number(json, "$.totals.expenseCents")).isEqualTo(300);
	}

	@Test
	void aWeekWithoutTransactionsIsZeroedWithSevenDays() throws Exception {
		World w = world();

		String json = body(w.owner(), "week=2026-W10");

		assertThat(number(json, "$.totals.incomeCents")).isZero();
		assertThat(number(json, "$.totals.expenseCents")).isZero();
		assertThat(JsonPath.<List<?>>read(json, "$.daily")).hasSize(7);
		assertThat(sum(json, "$.daily[*].expenseCents")).isZero();
		assertThat(JsonPath.<List<?>>read(json, "$.expenseByCategory")).isEmpty();
	}

	@Test
	void transfersDeletedAndUncategorizedFollowTheSameRulesAsTheMonth() throws Exception {
		World w = world();
		UUID other = w.fx().insertAccount(w.owner().workspaceId(), "Poupança", "SAVINGS", 0, true);
		LocalDate day = LocalDate.of(2026, 11, 4);
		w.ledger(w.expense(), "EXPENSE", 500, day);
		w.ledger(null, "EXPENSE", 250, day);
		w.fx().insertTransferPair(w.owner().workspaceId(), w.account(), other, w.owner().userId(), 9_999, day,
				"Reserva");
		w.fx().insertLedgerTransaction(w.owner().workspaceId(), w.account(), w.owner().userId(), w.expense(),
				"EXPENSE", 777, day, "Engano", true);

		String json = body(w.owner(), "week=2026-W45");

		assertThat(number(json, "$.totals.expenseCents")).isEqualTo(750);
		assertThat(JsonPath.<List<String>>read(json, "$.expenseByCategory[*].name")).containsExactly("Mercado",
				"Sem categoria");
	}

	@Test
	void thePeriodRequestedWinsOverTheCurrentMonth() throws Exception {
		World w = world();

		String json = body(w.owner(), "week=2026-W45");

		assertThat(JsonPath.<String>read(json, "$.period.type")).isEqualTo("WEEK");
	}

}
