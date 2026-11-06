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

/** US3: a evolução dia a dia e a comparação com o período anterior. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ComparisonTest {

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
	void everyDayOfTheMonthAppearsInCalendarOrderWithZerosOnQuietDays() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		String json = body(s.owner(), "month=2026-11");

		assertThat(JsonPath.<List<?>>read(json, "$.daily")).hasSize(30);
		assertThat(JsonPath.<String>read(json, "$.daily[0].date")).isEqualTo("2026-11-01");
		assertThat(JsonPath.<String>read(json, "$.daily[29].date")).isEqualTo("2026-11-30");
		assertThat(number(json, "$.daily[1].expenseCents")).isEqualTo(10_000);
		assertThat(number(json, "$.daily[2].expenseCents")).isEqualTo(5_000);
		assertThat(number(json, "$.daily[4].incomeCents")).isEqualTo(250_000);
		assertThat(number(json, "$.daily[0].incomeCents")).isZero();
		assertThat(number(json, "$.daily[10].expenseCents")).isZero();
		// o dia 6 só teve a transferência, que não conta
		assertThat(number(json, "$.daily[5].incomeCents")).isZero();
		assertThat(number(json, "$.daily[5].expenseCents")).isZero();
	}

	@Test
	void theDaysAddUpToTheTotals() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		String json = body(s.owner(), "month=2026-11");

		assertThat(sum(json, "$.daily[*].incomeCents")).isEqualTo(number(json, "$.totals.incomeCents"));
		assertThat(sum(json, "$.daily[*].expenseCents")).isEqualTo(number(json, "$.totals.expenseCents"));
	}

	@Test
	void februaryHasTwentyNineDaysInALeapYearAndTwentyEightOtherwise() throws Exception {
		World w = world();

		assertThat(JsonPath.<List<?>>read(body(w.owner(), "month=2024-02"), "$.daily")).hasSize(29);
		assertThat(JsonPath.<List<?>>read(body(w.owner(), "month=2025-02"), "$.daily")).hasSize(28);
	}

	@Test
	void theComparisonShowsTheChangeInCentsAndPercent() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		String json = body(s.owner(), "month=2026-11");

		assertThat(JsonPath.<String>read(json, "$.comparison.previousPeriod.id")).isEqualTo("2026-10");
		assertThat(JsonPath.<String>read(json, "$.comparison.previousPeriod.type")).isEqualTo("MONTH");
		assertThat(JsonPath.<String>read(json, "$.comparison.previousPeriod.startDate")).isEqualTo("2026-10-01");
		assertThat(JsonPath.<String>read(json, "$.comparison.previousPeriod.endDate")).isEqualTo("2026-10-31");
		assertThat(number(json, "$.comparison.expense.previousCents")).isEqualTo(20_000);
		assertThat(number(json, "$.comparison.expense.changeCents")).isEqualTo(-5_000);
		assertThat(JsonPath.<Double>read(json, "$.comparison.expense.changePercent")).isEqualTo(-25.00);
	}

	@Test
	void aZeroPreviousValueHasNoPercentButStillShowsTheChangeInCents() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		String json = body(s.owner(), "month=2026-11");

		assertThat(number(json, "$.comparison.income.previousCents")).isZero();
		assertThat(number(json, "$.comparison.income.changeCents")).isEqualTo(250_000);
		assertThat(JsonPath.<Object>read(json, "$.comparison.income.changePercent")).isNull();
	}

	@Test
	void theResultIsComparedWithTheAbsoluteBaseOfThePreviousResult() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		String json = body(s.owner(), "month=2026-11");

		assertThat(number(json, "$.comparison.result.previousCents")).isEqualTo(-20_000);
		assertThat(number(json, "$.comparison.result.changeCents")).isEqualTo(255_000);
		assertThat(JsonPath.<Double>read(json, "$.comparison.result.changePercent")).isEqualTo(1275.00);
	}

	@Test
	void theFirstAcceptedMonthComparesWithAnEmptyPreviousMonth() throws Exception {
		World w = world();
		w.ledger(w.expense(), "EXPENSE", 500, LocalDate.of(2000, 1, 15));

		String json = body(w.owner(), "month=2000-01");

		assertThat(JsonPath.<String>read(json, "$.comparison.previousPeriod.id")).isEqualTo("1999-12");
		assertThat(number(json, "$.comparison.expense.previousCents")).isZero();
		assertThat(number(json, "$.comparison.expense.changeCents")).isEqualTo(500);
		assertThat(JsonPath.<Object>read(json, "$.comparison.expense.changePercent")).isNull();
	}

	@Test
	void aDropToZeroIsMinusOneHundredPercent() throws Exception {
		World w = world();
		w.ledger(w.expense(), "EXPENSE", 1, LocalDate.of(2026, 10, 15));

		String json = body(w.owner(), "month=2026-11");

		assertThat(number(json, "$.comparison.expense.changeCents")).isEqualTo(-1);
		assertThat(JsonPath.<Double>read(json, "$.comparison.expense.changePercent")).isEqualTo(-100.00);
	}

	@Test
	void transfersAndDeletedTransactionsInThePreviousMonthDoNotCount() throws Exception {
		World w = world();
		UUID other = w.fx().insertAccount(w.owner().workspaceId(), "Poupança", "SAVINGS", 0, false);
		w.fx().insertTransferPair(w.owner().workspaceId(), w.account(), other, w.owner().userId(), 90_000,
				LocalDate.of(2026, 10, 15), "Reserva");
		w.fx().insertLedgerTransaction(w.owner().workspaceId(), w.account(), w.owner().userId(), w.expense(),
				"EXPENSE", 777, LocalDate.of(2026, 10, 16), "Engano", true);

		String json = body(w.owner(), "month=2026-11");

		assertThat(number(json, "$.comparison.expense.previousCents")).isZero();
		assertThat(number(json, "$.comparison.income.previousCents")).isZero();
	}

}
