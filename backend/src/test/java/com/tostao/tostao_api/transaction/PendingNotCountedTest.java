package com.tostao.tostao_api.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

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
import com.tostao.tostao_api.support.AuthSupport;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * O coração da funcionalidade (FR-003, SC-001): o lançamento pendente nunca conta no realizado. Dois workspaces
 * têm exatamente os mesmos lançamentos efetivados; só o workspace A tem, além deles, lançamentos pendentes
 * (despesa, receita, transferência, uma compra no cartão, um pagamento do cartão e um pendente excluído). Toda
 * consulta de realizado do A tem de devolver o mesmo que a do B.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PendingNotCountedTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	/** Os lançamentos efetivados, iguais nos dois workspaces, mais os limites de orçamento. */
	private PendingScenario realized() {
		WorkspaceFixtures f = fixtures();
		PendingScenario s = PendingScenario.create(f);
		UUID w = s.owner().workspaceId();
		UUID u = s.owner().userId();
		f.insertLedgerTransaction(w, s.checking(), u, s.salary(), "INCOME", 20_000, LocalDate.of(2026, 11, 5), "Salário", false);
		f.insertTransferPair(w, s.checking(), s.savings(), u, 3_000, LocalDate.of(2026, 11, 6), "Reserva");
		// o cartão em 2024, para a situação da fatura ser sempre a mesma (fechada e vencida)
		f.insertLedgerTransaction(w, s.card(), u, s.food(), "EXPENSE", 7_000, LocalDate.of(2024, 11, 5), "Compra no cartão", false);
		f.insertMonthlyBudget(w, LocalDate.of(2026, 11, 1), s.food(), 100_000, u);
		f.insertWeeklyBudget(w, LocalDate.of(2026, 11, 2), s.food(), 100_000, u);
		f.insertWeeklyBudget(w, LocalDate.of(2026, 11, 16), s.food(), 100_000, u);
		return s;
	}

	/** Só o workspace A: tudo o que está pendente. */
	private void pendings(PendingScenario s) {
		WorkspaceFixtures f = fixtures();
		UUID w = s.owner().workspaceId();
		UUID u = s.owner().userId();
		f.insertLedgerTransaction(w, s.checking(), u, s.food(), "EXPENSE", 80_000, LocalDate.of(2026, 11, 20), "Aluguel", false, "PENDING");
		f.insertLedgerTransaction(w, s.checking(), u, s.salary(), "INCOME", 30_000, LocalDate.of(2026, 11, 25), "Adiantamento", false, "PENDING");
		f.insertTransferPair(w, s.checking(), s.savings(), u, 10_000, LocalDate.of(2026, 11, 28), "Reserva futura", "PENDING");
		f.insertLedgerTransaction(w, s.card(), u, s.food(), "EXPENSE", 9_000, LocalDate.of(2024, 11, 10), "Compra pendente", false, "PENDING");
		f.insertTransferPair(w, s.checking(), s.card(), u, 4_000, LocalDate.of(2024, 11, 25), "Pagamento pendente", "PENDING");
		f.insertLedgerTransaction(w, s.checking(), u, s.food(), "EXPENSE", 1_234, LocalDate.of(2026, 11, 15), "Excluído", true, "PENDING");
	}

	private static String json(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private ResultActions getAs(Fixture who, String path) throws Exception {
		return mvc.perform(get("/api/v1/workspaces/" + who.workspaceId() + path).cookie(AuthSupport.session(who.token())));
	}

	private static void same(String path, String a, String b) {
		assertThat((Object) JsonPath.read(a, path)).as(path).isEqualTo(JsonPath.read(b, path));
	}

	@Test
	void pendingTransactionsNeverMoveAnyAccountBalance() throws Exception {
		PendingScenario a = realized();
		pendings(a);
		PendingScenario b = realized();

		String accountsA = json(getAs(a.owner(), "/accounts").andExpect(status().isOk()));
		String accountsB = json(getAs(b.owner(), "/accounts").andExpect(status().isOk()));
		same("$[*].balanceCents", accountsA, accountsB);
		same("$[*].initialBalanceCents", accountsA, accountsB);
		// os números, para o teste não passar por acaso
		assertThat((java.util.List<?>) JsonPath.read(accountsA, "$[?(@.name=='Corrente')].balanceCents")).isEqualTo(java.util.List.of(112_000));
		assertThat((java.util.List<?>) JsonPath.read(accountsA, "$[?(@.name=='Corrente')].pendingCents")).isEqualTo(java.util.List.of(-64_000));
		assertThat((java.util.List<?>) JsonPath.read(accountsB, "$[?(@.name=='Corrente')].pendingCents")).isEqualTo(java.util.List.of(0));

		String reportA = json(getAs(a.owner(), "/reports/balance"));
		String reportB = json(getAs(b.owner(), "/reports/balance"));
		same("$.totalBalanceCents", reportA, reportB);
		same("$.accounts[*].balanceCents", reportA, reportB);
	}

	@Test
	void theDashboardRealizedNumbersIgnoreThePendingOnesForAMonthAndForWeeks() throws Exception {
		PendingScenario a = realized();
		pendings(a);
		PendingScenario b = realized();

		for (String period : new String[] { "month=2026-11", "month=2026-12", "week=2026-W47", "week=2026-W48" }) {
			String da = json(getAs(a.owner(), "/dashboard?" + period).andExpect(status().isOk()));
			String db = json(getAs(b.owner(), "/dashboard?" + period).andExpect(status().isOk()));
			for (String path : new String[] { "$.totals", "$.daily", "$.comparison", "$.balances.totalBalanceCents",
					"$.balances.openingTotalCents", "$.balances.closingTotalCents", "$.balances.accounts[*].balanceCents",
					"$.balances.accounts[*].openingBalanceCents", "$.balances.accounts[*].closingBalanceCents",
					"$.expenseByCategory[*].totalCents", "$.incomeByCategory[*].totalCents" }) {
				assertThat((Object) JsonPath.read(da, path)).as(period + " " + path).isEqualTo(JsonPath.read(db, path));
			}
		}
		// o que está pendente só aparece no bloco "pending"
		String november = json(getAs(a.owner(), "/dashboard?month=2026-11"));
		assertThat((Number) JsonPath.read(november, "$.pending.incomeCents")).isEqualTo(30_000);
		assertThat((Number) JsonPath.read(november, "$.pending.expenseCents")).isEqualTo(80_000);
		assertThat((Number) JsonPath.read(november, "$.pending.count")).isEqualTo(2);
		assertThat((Number) JsonPath.read(november, "$.totals.expenseCents")).isEqualTo(5_000);
	}

	@Test
	void theMonthlyAndWeeklyBudgetSpendingIgnoresPendingExpenses() throws Exception {
		PendingScenario a = realized();
		pendings(a);
		PendingScenario b = realized();

		for (String path : new String[] { "/monthly-budgets?month=2026-11", "/budgets?week=2026-W45", "/budgets?week=2026-W47" }) {
			String ba = json(getAs(a.owner(), path).andExpect(status().isOk()));
			String bb = json(getAs(b.owner(), path).andExpect(status().isOk()));
			same("$.totalSpentCents", ba, bb);
			same("$.spentOutsideCategoryLimitsCents", ba, bb);
			same("$.budgets[*].spentCents", ba, bb);
			same("$.budgets[*].remainingCents", ba, bb);
			same("$.budgets[*].status", ba, bb);
		}
		// em novembro, só os 5000 efetivados de Alimentação contam (os 80000 pendentes não)
		assertThat((Number) JsonPath.read(json(getAs(a.owner(), "/monthly-budgets?month=2026-11")), "$.totalSpentCents"))
				.isEqualTo(5_000);
		// a semana do aluguel pendente não tem gasto nenhum
		assertThat((Number) JsonPath.read(json(getAs(a.owner(), "/budgets?week=2026-W47")), "$.totalSpentCents"))
				.isEqualTo(0);
	}

	@Test
	void theCardInvoiceAndSummaryIgnoreAPendingPurchaseAndAPendingPayment() throws Exception {
		PendingScenario a = realized();
		pendings(a);
		PendingScenario b = realized();

		String ia = json(getAs(a.owner(), "/accounts/" + a.card() + "/invoices/2024-11").andExpect(status().isOk()));
		String ib = json(getAs(b.owner(), "/accounts/" + b.card() + "/invoices/2024-11").andExpect(status().isOk()));
		for (String path : new String[] { "$.totalCents", "$.paidCents", "$.remainingCents", "$.status", "$.lines[*].chargeCents" }) {
			same(path, ia, ib);
		}
		// só a compra efetivada de 7000 está na fatura; o pagamento pendente de 4000 não conta como pago
		assertThat((Number) JsonPath.read(ia, "$.totalCents")).isEqualTo(7_000);
		assertThat((Number) JsonPath.read(ia, "$.paidCents")).isEqualTo(0);
		assertThat((java.util.List<?>) JsonPath.read(ia, "$.lines")).hasSize(1);

		String la = json(getAs(a.owner(), "/accounts/" + a.card() + "/invoices?from=2024-10&to=2024-12"));
		String lb = json(getAs(b.owner(), "/accounts/" + b.card() + "/invoices?from=2024-10&to=2024-12"));
		same("$.invoices[*].totalCents", la, lb);
		same("$.invoices[*].paidCents", la, lb);

		String sa = json(getAs(a.owner(), "/accounts/" + a.card() + "/card-summary"));
		String sb = json(getAs(b.owner(), "/accounts/" + b.card() + "/card-summary"));
		same("$.owedCents", sa, sb);
		same("$.availableCents", sa, sb);
		assertThat((Number) JsonPath.read(sa, "$.owedCents")).isEqualTo(7_000);
	}

	@Test
	void clearingEveryPendingMakesThemCountInEveryRealizedNumber() throws Exception {
		PendingScenario a = realized();
		pendings(a);
		java.util.List<UUID> ids = owner.client().sql("select id from transactions where workspace_id = :w "
				+ "and status = 'PENDING' and deleted_at is null").param("w", a.owner().workspaceId()).query(UUID.class).list();

		for (UUID id : ids) {
			PendingScenario.clear(mvc, a.owner(), id).andExpect(status().isOk());
		}

		// 100000 inicial - 5000 + 20000 - 3000 (efetivados) - 80000 + 30000 - 10000 - 4000 (agora efetivados)
		String accounts = json(getAs(a.owner(), "/accounts"));
		assertThat((java.util.List<?>) JsonPath.read(accounts, "$[?(@.name=='Corrente')].balanceCents")).isEqualTo(java.util.List.of(48_000));
		assertThat((java.util.List<?>) JsonPath.read(accounts, "$[?(@.name=='Corrente')].pendingCents")).isEqualTo(java.util.List.of(0));
		assertThat((Number) JsonPath.read(json(getAs(a.owner(), "/monthly-budgets?month=2026-11")), "$.totalSpentCents"))
				.isEqualTo(85_000);
		assertThat((Number) JsonPath.read(json(getAs(a.owner(), "/accounts/" + a.card() + "/invoices/2024-11")), "$.totalCents"))
				.isEqualTo(16_000);
		assertThat((Number) JsonPath.read(json(getAs(a.owner(), "/accounts/" + a.card() + "/invoices/2024-11")), "$.paidCents"))
				.isEqualTo(4_000);
		assertThat((Number) JsonPath.read(json(getAs(a.owner(), "/dashboard?month=2026-11")), "$.pending.count")).isEqualTo(0);
	}

}
