package com.tostao.tostao_api.recurrence;

import static com.tostao.tostao_api.recurrence.RecurrenceScenario.createRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.today;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.with;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.tostao.tostao_api.automation.AutomationService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * SC-006: as ocorrências pendentes de uma transferência recorrente nunca entram no saldo, no dashboard, nos orçamentos nem na
 * fatura; as efetivadas entram em exatos centavos nas duas contas sem mudar a soma dos saldos, e um pagamento recorrente do
 * cartão efetivado reduz o devido.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TransferRecurrenceEffectsTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	AutomationService automation;

	/** O banco é compartilhado: deixa de lado as recorrências e os lançamentos marcados de outros testes. */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
		owner.client().sql("select auto_clear_due(date '2100-12-31')").query(Integer.class).single();
	}

	private RecurrenceScenario scenario() {
		return RecurrenceScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	private static String json(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private String getAs(Fixture who, String path) throws Exception {
		return json(mvc.perform(get("/api/v1/workspaces/" + who.workspaceId() + path).cookie(session(who.token()))).andExpect(status().isOk()));
	}

	private static Map<String, String> transfer(UUID from, UUID to, String frequency, LocalDate start, boolean autoClear) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("accountId", "\"" + from + "\"");
		fields.put("destinationAccountId", "\"" + to + "\"");
		fields.put("type", "\"TRANSFER\"");
		fields.put("amountCents", "50000");
		fields.put("description", "\"Reserva\"");
		fields.put("frequency", "\"" + frequency + "\"");
		fields.put("startOn", "\"" + start + "\"");
		fields.put("autoClear", String.valueOf(autoClear));
		return fields;
	}

	private static int balanceOf(String accounts, String name) {
		return JsonPath.<List<Integer>>read(accounts, "$[?(@.name=='" + name + "')].balanceCents").get(0);
	}

	private static int pendingOf(String accounts, String name) {
		return JsonPath.<List<Integer>>read(accounts, "$[?(@.name=='" + name + "')].pendingCents").get(0);
	}

	@Test
	void pendingOccurrencesNeverMoveABalanceTheDashboardOrABudget() throws Exception {
		RecurrenceScenario s = scenario();
		UUID w = s.owner().workspaceId();
		YearMonth month = YearMonth.from(today());
		new WorkspaceFixtures(owner, tokens).insertMonthlyBudget(w, month.atDay(1), null, 900_000, s.owner().userId());
		String accountsBefore = getAs(s.owner(), "/accounts");
		String dashboardBefore = getAs(s.owner(), "/dashboard?month=" + month);

		createRecurrence(mvc, s.owner(), with(transfer(s.checking(), s.savings(), "MONTHLY", today(), false))).andExpect(status().isCreated());

		String accounts = getAs(s.owner(), "/accounts");
		assertThat(balanceOf(accounts, "Corrente")).isEqualTo(balanceOf(accountsBefore, "Corrente")).isEqualTo(500_000);
		assertThat(balanceOf(accounts, "Poupança")).isEqualTo(0);
		// o pendente aparece como pendente (13 ocorrências), não como saldo
		assertThat(pendingOf(accounts, "Corrente")).isEqualTo(-650_000);
		assertThat(pendingOf(accounts, "Poupança")).isEqualTo(650_000);
		String dashboard = getAs(s.owner(), "/dashboard?month=" + month);
		assertThat(JsonPath.<Object>read(dashboard, "$.totals")).isEqualTo(JsonPath.read(dashboardBefore, "$.totals"));
		assertThat(JsonPath.<Object>read(dashboard, "$.balances.accounts[*].balanceCents")).isEqualTo(JsonPath.read(dashboardBefore, "$.balances.accounts[*].balanceCents"));
		assertThat(JsonPath.<Object>read(dashboard, "$.balances.totalBalanceCents")).isEqualTo(JsonPath.read(dashboardBefore, "$.balances.totalBalanceCents"));
		// uma transferência nunca é gasto: o orçamento do mês segue sem nada gasto
		String budgets = getAs(s.owner(), "/monthly-budgets?month=" + month);
		assertThat(JsonPath.<Integer>read(budgets, "$.totalSpentCents")).isZero();
	}

	@Test
	void clearedOccurrencesMoveBothAccountsInExactCentsAndTheTotalStaysThe() throws Exception {
		RecurrenceScenario s = scenario();
		// semanal, começando há 14 dias, com o marcador: as ocorrências de há 14 dias, há 7 dias e de hoje são efetivadas pela rotina
		createRecurrence(mvc, s.owner(), with(transfer(s.checking(), s.savings(), "WEEKLY", today().minusDays(14), true))).andExpect(status().isCreated());
		String totalBefore = getAs(s.owner(), "/reports/balance");

		automation.runOnce(today());

		String accounts = getAs(s.owner(), "/accounts");
		assertThat(balanceOf(accounts, "Corrente")).isEqualTo(500_000 - 150_000);
		assertThat(balanceOf(accounts, "Poupança")).isEqualTo(150_000);
		String totalAfter = getAs(s.owner(), "/reports/balance");
		assertThat(JsonPath.<Integer>read(totalAfter, "$.totalBalanceCents")).isEqualTo(JsonPath.<Integer>read(totalBefore, "$.totalBalanceCents"));
		// o dashboard do mês mostra os saldos por conta, mas não muda receita nem despesa
		String dashboard = getAs(s.owner(), "/dashboard?month=" + YearMonth.from(today()));
		assertThat(JsonPath.<Integer>read(dashboard, "$.totals.incomeCents")).isZero();
		assertThat(JsonPath.<Integer>read(dashboard, "$.totals.expenseCents")).isZero();
		assertThat(JsonPath.<Integer>read(dashboard, "$.balances.totalBalanceCents")).isEqualTo(JsonPath.<Integer>read(totalBefore, "$.totalBalanceCents"));
	}

	@Test
	void aRecurringCardPaymentOnceClearedReducesWhatIsOwed() throws Exception {
		RecurrenceScenario s = scenario();
		UUID w = s.owner().workspaceId();
		new WorkspaceFixtures(owner, tokens).insertLedgerTransaction(w, s.card(), s.owner().userId(), s.groceries(), "EXPENSE", 200_000,
				today().minusDays(30), "Compras do mês", false);
		createRecurrence(mvc, s.owner(), with(transfer(s.checking(), s.card(), "WEEKLY", today().minusDays(14), true))).andExpect(status().isCreated());
		String before = getAs(s.owner(), "/accounts/" + s.card() + "/card-summary");

		// pendentes: o devido não mudou
		assertThat(JsonPath.<Integer>read(before, "$.owedCents")).isEqualTo(200_000);

		automation.runOnce(today());

		String after = getAs(s.owner(), "/accounts/" + s.card() + "/card-summary");
		assertThat(JsonPath.<Integer>read(after, "$.owedCents")).isEqualTo(200_000 - 150_000);
	}

	@Test
	void theListingOfTransactionsShowsTheLegOfEachAccount() throws Exception {
		RecurrenceScenario s = scenario();
		createRecurrence(mvc, s.owner(), with(transfer(s.checking(), s.savings(), "MONTHLY", today(), false))).andExpect(status().isCreated());

		String checking = getAs(s.owner(), "/transactions?size=100&accountId=" + s.checking());
		String savings = getAs(s.owner(), "/transactions?size=100&accountId=" + s.savings());

		assertThat(JsonPath.<Integer>read(checking, "$.totalItems")).isEqualTo(13);
		assertThat(JsonPath.<Integer>read(savings, "$.totalItems")).isEqualTo(13);
		assertThat(JsonPath.<List<Integer>>read(checking, "$.items[*].amountCents")).containsOnly(-50_000);
		assertThat(JsonPath.<List<Integer>>read(savings, "$.items[*].amountCents")).containsOnly(50_000);
		assertThat(JsonPath.<List<String>>read(checking, "$.items[*].counterpartAccountId")).containsOnly(s.savings().toString());
		assertThat(JsonPath.<List<String>>read(savings, "$.items[*].counterpartAccountId")).containsOnly(s.checking().toString());
	}

	@Test
	void clearingOneLegByHandClearsTheOtherAndTheRecurrenceKeepsTheRest() throws Exception {
		RecurrenceScenario s = scenario();
		String detail = json(createRecurrence(mvc, s.owner(), with(transfer(s.checking(), s.savings(), "MONTHLY", today(), false)))
				.andExpect(status().isCreated()));
		UUID inflow = UUID.fromString(JsonPath.<List<String>>read(detail, "$.occurrences[?(@.occurrenceNumber == 1 && @.amountCents > 0)].id").get(0));

		clear(mvc, s.owner(), inflow).andExpect(status().isOk());

		String accounts = getAs(s.owner(), "/accounts");
		assertThat(balanceOf(accounts, "Corrente")).isEqualTo(450_000);
		assertThat(balanceOf(accounts, "Poupança")).isEqualTo(50_000);
		assertThat(pendingOf(accounts, "Corrente")).isEqualTo(-600_000);
	}

}
