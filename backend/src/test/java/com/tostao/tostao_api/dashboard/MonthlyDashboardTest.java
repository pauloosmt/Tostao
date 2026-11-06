package com.tostao.tostao_api.dashboard;

import static com.tostao.tostao_api.dashboard.DashboardScenario.dashboard;
import static org.assertj.core.api.Assertions.assertThat;
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

/** US1: o resumo do mês (receitas, despesas e resultado), sem transferências nem excluídos. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MonthlyDashboardTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private static long number(String json, String path) {
		return ((Number) JsonPath.read(json, path)).longValue();
	}

	private String body(Fixture who, String query) throws Exception {
		return dashboard(mvc, who, query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	/** Workspace com uma conta e uma categoria de despesa e uma de receita, sem lançamentos. */
	private record Empty(Fixture owner, UUID account, UUID expense, UUID income) {
	}

	private Empty empty() {
		WorkspaceFixtures fx = fixtures();
		Fixture owner = fx.workspaceWithOwner();
		UUID account = fx.insertAccount(owner.workspaceId(), "Corrente", "CHECKING", 0, false);
		UUID expense = fx.insertCategory(owner.workspaceId(), null, "Mercado", "EXPENSE", false);
		UUID income = fx.insertCategory(owner.workspaceId(), null, "Salário", "INCOME", false);
		return new Empty(owner, account, expense, income);
	}

	private void ledger(Empty e, UUID category, String type, long cents, LocalDate date) {
		fixtures().insertLedgerTransaction(e.owner().workspaceId(), e.account(), e.owner().userId(), category, type,
				cents, date, "Teste", false);
	}

	@Test
	void novemberTotalsIgnoreTransfersAndDeletedTransactions() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		dashboard(mvc, s.owner(), "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.period.type").value("MONTH"))
				.andExpect(jsonPath("$.period.id").value("2026-11"))
				.andExpect(jsonPath("$.period.startDate").value("2026-11-01"))
				.andExpect(jsonPath("$.period.endDate").value("2026-11-30"))
				.andExpect(jsonPath("$.totals.incomeCents").value(250000))
				.andExpect(jsonPath("$.totals.expenseCents").value(15000))
				.andExpect(jsonPath("$.totals.resultCents").value(235000));
	}

	@Test
	void theResultIsNegativeWhenTheMonthHadOnlyExpenses() throws Exception {
		Empty e = empty();
		ledger(e, e.expense(), "EXPENSE", 8_000, LocalDate.of(2026, 11, 10));

		dashboard(mvc, e.owner(), "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.totals.incomeCents").value(0))
				.andExpect(jsonPath("$.totals.expenseCents").value(8000))
				.andExpect(jsonPath("$.totals.resultCents").value(-8000));
	}

	@Test
	void theFirstAndLastDayOfTheMonthCountButTheNeighboringDaysDoNot() throws Exception {
		Empty e = empty();
		ledger(e, e.expense(), "EXPENSE", 100, LocalDate.of(2026, 11, 1));
		ledger(e, e.expense(), "EXPENSE", 200, LocalDate.of(2026, 11, 30));
		ledger(e, e.expense(), "EXPENSE", 4_000, LocalDate.of(2026, 10, 31));
		ledger(e, e.expense(), "EXPENSE", 8_000, LocalDate.of(2026, 12, 1));

		String json = body(e.owner(), "month=2026-11");

		assertThat(number(json, "$.totals.expenseCents")).isEqualTo(300);
	}

	@Test
	void archivedAccountsAndCategoriesStillCount() throws Exception {
		WorkspaceFixtures fx = fixtures();
		Fixture owner = fx.workspaceWithOwner();
		UUID archivedAccount = fx.insertAccount(owner.workspaceId(), "Antiga", "CHECKING", 0, true);
		UUID archivedCategory = fx.insertCategory(owner.workspaceId(), null, "Velha", "EXPENSE", true);
		fx.insertLedgerTransaction(owner.workspaceId(), archivedAccount, owner.userId(), archivedCategory, "EXPENSE",
				1_234, LocalDate.of(2026, 11, 3), "Antigo", false);

		assertThat(number(body(owner, "month=2026-11"), "$.totals.expenseCents")).isEqualTo(1_234);
	}

	@Test
	void transactionsWithoutACategoryStillCountInTheTotals() throws Exception {
		Empty e = empty();
		ledger(e, null, "EXPENSE", 700, LocalDate.of(2026, 11, 3));
		ledger(e, null, "INCOME", 900, LocalDate.of(2026, 11, 4));

		String json = body(e.owner(), "month=2026-11");

		assertThat(number(json, "$.totals.expenseCents")).isEqualTo(700);
		assertThat(number(json, "$.totals.incomeCents")).isEqualTo(900);
	}

	@Test
	void aMonthWithoutTransactionsIsAnEmptyDashboardNotAnError() throws Exception {
		Empty e = empty();

		dashboard(mvc, e.owner(), "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.totals.incomeCents").value(0))
				.andExpect(jsonPath("$.totals.expenseCents").value(0))
				.andExpect(jsonPath("$.totals.resultCents").value(0));
	}

	@Test
	void centsAddUpExactly() throws Exception {
		Empty e = empty();
		ledger(e, e.expense(), "EXPENSE", 10, LocalDate.of(2026, 11, 3));
		ledger(e, e.expense(), "EXPENSE", 20, LocalDate.of(2026, 11, 3));

		assertThat(number(body(e.owner(), "month=2026-11"), "$.totals.expenseCents")).isEqualTo(30);
	}

	@Test
	void hugeAmountsAddUpExactlyWithoutOverflow() throws Exception {
		Empty e = empty();
		long max = 1_000_000_000_000L;
		for (int i = 0; i < 5; i++) {
			ledger(e, e.expense(), "EXPENSE", max, LocalDate.of(2026, 11, 3 + i));
			ledger(e, e.income(), "INCOME", max, LocalDate.of(2026, 11, 3 + i));
		}

		String json = body(e.owner(), "month=2026-11");

		assertThat(number(json, "$.totals.expenseCents")).isEqualTo(5 * max);
		assertThat(number(json, "$.totals.incomeCents")).isEqualTo(5 * max);
		assertThat(number(json, "$.totals.resultCents")).isZero();
	}

	@Test
	void theResponseIsNeverCached() throws Exception {
		Empty e = empty();

		dashboard(mvc, e.owner(), "month=2026-11").andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
	}

	@Test
	void withoutAMonthTheCurrentMonthIsUsed() throws Exception {
		Empty e = empty();
		String before = Period.resolve(null, null, Instant.now()).id();

		String json = body(e.owner(), "");

		String after = Period.resolve(null, null, Instant.now()).id();
		assertThat(JsonPath.<String>read(json, "$.period.type")).isEqualTo("MONTH");
		assertThat(JsonPath.<String>read(json, "$.period.id")).isIn(before, after);
	}

	@Test
	void eachWorkspaceOnlySumsItsOwnTransactions() throws Exception {
		Empty first = empty();
		Empty second = empty();
		ledger(first, first.expense(), "EXPENSE", 1_000, LocalDate.of(2026, 11, 3));
		ledger(second, second.expense(), "EXPENSE", 99_000, LocalDate.of(2026, 11, 3));

		assertThat(number(body(first.owner(), "month=2026-11"), "$.totals.expenseCents")).isEqualTo(1_000);
		assertThat(number(body(second.owner(), "month=2026-11"), "$.totals.expenseCents")).isEqualTo(99_000);
	}

}
