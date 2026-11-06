package com.tostao.tostao_api.dashboard;

import static com.tostao.tostao_api.dashboard.DashboardScenario.dashboard;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** US5: saldos no painel — o de hoje, o de abertura, o de fechamento e o saldo total de cada dia. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DashboardBalancesTest {

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

	private static List<Long> longs(String json, String path) {
		return JsonPath.<List<Number>>read(json, path).stream().map(Number::longValue).toList();
	}

	private static long accountValue(String json, String account, String field) {
		List<Number> found = JsonPath.read(json, "$.balances.accounts[?(@.name=='" + account + "')]." + field);
		return found.get(0).longValue();
	}

	@Test
	void theScenarioWeekOpensAndClosesAsExpectedPerAccountAndInTotal() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		String json = body(s.owner(), "week=2026-W45");

		assertThat(JsonPath.<List<String>>read(json, "$.balances.accounts[*].name")).containsExactly("Corrente",
				"Poupança");
		assertThat(accountValue(json, "Corrente", "openingBalanceCents")).isEqualTo(80_000);
		assertThat(accountValue(json, "Corrente", "closingBalanceCents")).isEqualTo(285_000);
		assertThat(accountValue(json, "Corrente", "balanceCents")).isEqualTo(285_000);
		assertThat(accountValue(json, "Poupança", "openingBalanceCents")).isZero();
		assertThat(accountValue(json, "Poupança", "closingBalanceCents")).isEqualTo(30_000);
		assertThat(number(json, "$.balances.openingTotalCents")).isEqualTo(80_000);
		assertThat(number(json, "$.balances.closingTotalCents")).isEqualTo(315_000);
		assertThat(number(json, "$.balances.totalBalanceCents")).isEqualTo(315_000);
	}

	@Test
	void theDailyBalancesFollowTheMovementAndTheTransferDoesNotChangeTheTotal() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		String json = body(s.owner(), "week=2026-W45");

		assertThat(longs(json, "$.daily[*].balanceCents")).containsExactly(70_000L, 65_000L, 65_000L, 315_000L,
				315_000L, 315_000L, 315_000L);
		assertThat(number(json, "$.daily[6].balanceCents")).isEqualTo(number(json, "$.balances.closingTotalCents"));
	}

	@Test
	void todaysBalancesAreIdenticalToTheSpec002Report() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		String panel = body(s.owner(), "week=2026-W45");
		String report = mvc.perform(get("/api/v1/workspaces/" + s.owner().workspaceId() + "/reports/balance")
				.cookie(session(s.owner().token()))).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();

		assertThat(number(panel, "$.balances.totalBalanceCents")).isEqualTo(number(report, "$.totalBalanceCents"));
		List<String> reportIds = JsonPath.read(report, "$.accounts[*].id");
		List<String> panelIds = JsonPath.read(panel, "$.balances.accounts[*].id");
		assertThat(panelIds).containsExactlyElementsOf(reportIds);
		assertThat(longs(panel, "$.balances.accounts[*].balanceCents"))
				.isEqualTo(longs(report, "$.accounts[*].balanceCents"));
	}

	@Test
	void aPastMonthKeepsTodaysBalanceButOpensAndClosesInThatMonth() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		String json = body(s.owner(), "month=2026-10");

		assertThat(number(json, "$.balances.totalBalanceCents")).isEqualTo(315_000);
		assertThat(number(json, "$.balances.openingTotalCents")).isEqualTo(100_000);
		assertThat(number(json, "$.balances.closingTotalCents")).isEqualTo(80_000);
		assertThat(longs(json, "$.daily[*].balanceCents")).hasSize(31).startsWith(100_000L).endsWith(80_000L);
	}

	@Test
	void aCutoffBeforeAnyTransactionIsTheSumOfTheInitialBalances() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		String json = body(s.owner(), "month=2026-01");

		assertThat(number(json, "$.balances.openingTotalCents")).isEqualTo(100_000);
		assertThat(number(json, "$.balances.closingTotalCents")).isEqualTo(100_000);
		assertThat(accountValue(json, "Corrente", "openingBalanceCents")).isEqualTo(100_000);
		assertThat(accountValue(json, "Poupança", "closingBalanceCents")).isZero();
	}

	@Test
	void archivedAccountsStayOutOfEveryBalanceEvenForATransferToThem() throws Exception {
		WorkspaceFixtures fx = fixtures();
		Fixture owner = fx.workspaceWithOwner();
		UUID active = fx.insertAccount(owner.workspaceId(), "Ativa", "CHECKING", 1_000, false);
		UUID archived = fx.insertAccount(owner.workspaceId(), "Arquivada", "CHECKING", 500, true);
		fx.insertTransferPair(owner.workspaceId(), active, archived, owner.userId(), 300, LocalDate.of(2026, 11, 4),
				"Para a arquivada");

		String json = body(owner, "week=2026-W45");

		assertThat(JsonPath.<List<String>>read(json, "$.balances.accounts[*].name")).containsExactly("Ativa");
		assertThat(number(json, "$.balances.totalBalanceCents")).isEqualTo(700);
		assertThat(number(json, "$.balances.openingTotalCents")).isEqualTo(1_000);
		assertThat(number(json, "$.balances.closingTotalCents")).isEqualTo(700);
		// como na spec 002, a saída para uma conta arquivada reduz o total do dia
		assertThat(longs(json, "$.daily[*].balanceCents")).containsExactly(1_000L, 1_000L, 700L, 700L, 700L, 700L,
				700L);
	}

	@Test
	void aTransferBetweenActiveAccountsNeverChangesTheDailyTotal() throws Exception {
		WorkspaceFixtures fx = fixtures();
		Fixture owner = fx.workspaceWithOwner();
		UUID a = fx.insertAccount(owner.workspaceId(), "A", "CHECKING", 1_000, false);
		UUID b = fx.insertAccount(owner.workspaceId(), "B", "SAVINGS", 0, false);
		fx.insertTransferPair(owner.workspaceId(), a, b, owner.userId(), 400, LocalDate.of(2026, 11, 4), "Reserva");

		String json = body(owner, "week=2026-W45");

		assertThat(longs(json, "$.daily[*].balanceCents")).containsOnly(1_000L);
		assertThat(accountValue(json, "A", "closingBalanceCents")).isEqualTo(600);
		assertThat(accountValue(json, "B", "closingBalanceCents")).isEqualTo(400);
	}

	@Test
	void deletedTransactionsNeverCountEvenInPastBalances() throws Exception {
		WorkspaceFixtures fx = fixtures();
		Fixture owner = fx.workspaceWithOwner();
		UUID account = fx.insertAccount(owner.workspaceId(), "Corrente", "CHECKING", 0, false);
		fx.insertLedgerTransaction(owner.workspaceId(), account, owner.userId(), null, "INCOME", 5_000,
				LocalDate.of(2026, 10, 1), "Antigo", true);
		fx.insertLedgerTransaction(owner.workspaceId(), account, owner.userId(), null, "INCOME", 200,
				LocalDate.of(2026, 10, 2), "Valido", false);

		String json = body(owner, "month=2026-11");

		assertThat(number(json, "$.balances.openingTotalCents")).isEqualTo(200);
		assertThat(number(json, "$.balances.totalBalanceCents")).isEqualTo(200);
	}

	@Test
	void futureDatedTransactionsInsideThePeriodCountFromTheirDay() throws Exception {
		WorkspaceFixtures fx = fixtures();
		Fixture owner = fx.workspaceWithOwner();
		UUID account = fx.insertAccount(owner.workspaceId(), "Corrente", "CHECKING", 1_000, false);
		fx.insertLedgerTransaction(owner.workspaceId(), account, owner.userId(), null, "EXPENSE", 250,
				LocalDate.of(2099, 6, 10), "Futuro", false);

		String json = body(owner, "month=2099-06");

		assertThat(number(json, "$.balances.openingTotalCents")).isEqualTo(1_000);
		assertThat(number(json, "$.balances.closingTotalCents")).isEqualTo(750);
		assertThat(number(json, "$.daily[8].balanceCents")).isEqualTo(1_000);
		assertThat(number(json, "$.daily[9].balanceCents")).isEqualTo(750);
		assertThat(number(json, "$.daily[29].balanceCents")).isEqualTo(750);
	}

	@Test
	void aCreditCardDebtReducesTheTotal() throws Exception {
		WorkspaceFixtures fx = fixtures();
		Fixture owner = fx.workspaceWithOwner();
		fx.insertAccount(owner.workspaceId(), "Corrente", "CHECKING", 10_000, false);
		fx.insertAccount(owner.workspaceId(), "Cartão", "CREDIT_CARD", -3_000, false);

		String json = body(owner, "month=2026-11");

		assertThat(number(json, "$.balances.totalBalanceCents")).isEqualTo(7_000);
		assertThat(number(json, "$.balances.openingTotalCents")).isEqualTo(7_000);
		assertThat(accountValue(json, "Cartão", "balanceCents")).isEqualTo(-3_000);
	}

	@Test
	void aWorkspaceWithoutAccountsHasEmptyBalancesAndZeroedDays() throws Exception {
		Fixture owner = fixtures().workspaceWithOwner();

		String json = body(owner, "week=2026-W45");

		assertThat(JsonPath.<List<?>>read(json, "$.balances.accounts")).isEmpty();
		assertThat(number(json, "$.balances.totalBalanceCents")).isZero();
		assertThat(number(json, "$.balances.openingTotalCents")).isZero();
		assertThat(number(json, "$.balances.closingTotalCents")).isZero();
		assertThat(longs(json, "$.daily[*].balanceCents")).containsOnly(0L).hasSize(7);
	}

	@Test
	void anotherWorkspacesAccountsNeverEnterTheBalances() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());
		DashboardScenario other = DashboardScenario.create(fixtures());

		String json = body(s.owner(), "week=2026-W45");

		assertThat(JsonPath.<List<?>>read(json, "$.balances.accounts")).hasSize(2);
		assertThat(number(json, "$.balances.totalBalanceCents")).isEqualTo(315_000);
		assertThat(other.owner().workspaceId()).isNotEqualTo(s.owner().workspaceId());
	}

}
