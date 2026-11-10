package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.transaction.InstallmentScenario.purchase;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchaseBody;
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
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.AuthSupport;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * A integração com as specs anteriores (FR-005, SC-003, SC-011): as parcelas pendentes nunca contam no realizado.
 * O workspace A registra três compras parceladas (a 1ª parcela efetivada, as demais pendentes); o workspace B só tem
 * a 1ª parcela de cada uma, como despesa comum, na mesma data, conta e categoria. Toda consulta de realizado do A tem
 * de devolver o mesmo que a do B. Depois, efetivar as pendentes do A as faz entrar em exatos centavos.
 *
 * <ul>
 * <li>Notebook: 300000 em 3 na Corrente (Eletrônicos), de 2026-11-10: parcelas de 100000 em nov, dez e jan.</li>
 * <li>Mercado: 6000 em 3 na Corrente (Alimentação), de 2026-11-12: parcelas de 2000 em nov, dez e jan.</li>
 * <li>Fone: 9000 em 3 no Visa (Alimentação), de 2024-11-05: parcelas de 3000, uma por fatura (2024-11, 12 e 2025-01).</li>
 * </ul>
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InstallmentCountsTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	/** Cenário com limites mensais (nov, dez e jan) e semanal (semana de 2026-12-07) de Alimentação. */
	private InstallmentScenario base() {
		WorkspaceFixtures f = fixtures();
		InstallmentScenario s = InstallmentScenario.create(f);
		UUID w = s.owner().workspaceId();
		UUID u = s.owner().userId();
		f.insertMonthlyBudget(w, LocalDate.of(2026, 11, 1), s.food(), 100_000, u);
		f.insertMonthlyBudget(w, LocalDate.of(2026, 12, 1), s.food(), 100_000, u);
		f.insertMonthlyBudget(w, LocalDate.of(2027, 1, 1), s.food(), 100_000, u);
		f.insertWeeklyBudget(w, LocalDate.of(2026, 12, 7), s.food(), 100_000, u);
		return s;
	}

	/** O workspace A: as três compras parceladas, pela API. */
	private InstallmentScenario withInstallments() throws Exception {
		InstallmentScenario s = base();
		purchase(mvc, s.owner(), purchaseBody(s.checking(), s.gadgets(), 300_000, 3, "2026-11-10", "Notebook", null))
				.andExpect(status().isCreated());
		purchase(mvc, s.owner(), purchaseBody(s.checking(), s.food(), 6_000, 3, "2026-11-12", "Mercado", null))
				.andExpect(status().isCreated());
		purchase(mvc, s.owner(), purchaseBody(s.card(), s.food(), 9_000, 3, "2024-11-05", "Fone", null))
				.andExpect(status().isCreated());
		return s;
	}

	/** O workspace B: só a 1ª parcela de cada compra, como despesa comum efetivada. */
	private InstallmentScenario onlyTheFirstOnes() {
		WorkspaceFixtures f = fixtures();
		InstallmentScenario s = base();
		UUID w = s.owner().workspaceId();
		UUID u = s.owner().userId();
		f.insertLedgerTransaction(w, s.checking(), u, s.gadgets(), "EXPENSE", 100_000, LocalDate.of(2026, 11, 10), "Notebook (1/3)", false);
		f.insertLedgerTransaction(w, s.checking(), u, s.food(), "EXPENSE", 2_000, LocalDate.of(2026, 11, 12), "Mercado (1/3)", false);
		f.insertLedgerTransaction(w, s.card(), u, s.food(), "EXPENSE", 3_000, LocalDate.of(2024, 11, 5), "Fone (1/3)", false);
		return s;
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

	private static long number(String json, String path) {
		return ((Number) JsonPath.read(json, path)).longValue();
	}

	@Test
	void pendingInstallmentsNeverMoveAnyAccountBalance() throws Exception {
		InstallmentScenario a = withInstallments();
		InstallmentScenario b = onlyTheFirstOnes();

		String accountsA = json(getAs(a.owner(), "/accounts").andExpect(status().isOk()));
		String accountsB = json(getAs(b.owner(), "/accounts").andExpect(status().isOk()));
		same("$[*].balanceCents", accountsA, accountsB);
		assertThat((List<?>) JsonPath.read(accountsA, "$[?(@.name=='Corrente')].balanceCents")).isEqualTo(List.of(398_000));
		// o previsto, sim, inclui as parcelas futuras (100000 + 2000 em dezembro e em janeiro)
		assertThat((List<?>) JsonPath.read(accountsA, "$[?(@.name=='Corrente')].pendingCents")).isEqualTo(List.of(-204_000));
		assertThat((List<?>) JsonPath.read(accountsA, "$[?(@.name=='Corrente')].projectedBalanceCents"))
				.isEqualTo(List.of(194_000));
		assertThat((List<?>) JsonPath.read(accountsB, "$[?(@.name=='Corrente')].pendingCents")).isEqualTo(List.of(0));

		String reportA = json(getAs(a.owner(), "/reports/balance"));
		String reportB = json(getAs(b.owner(), "/reports/balance"));
		same("$.totalBalanceCents", reportA, reportB);
		same("$.accounts[*].balanceCents", reportA, reportB);
	}

	@Test
	void theDashboardRealizedNumbersIgnoreThePendingInstallments() throws Exception {
		InstallmentScenario a = withInstallments();
		InstallmentScenario b = onlyTheFirstOnes();

		for (String period : new String[] { "month=2026-11", "month=2026-12", "month=2027-01", "week=2026-W46",
				"week=2026-W50" }) {
			String da = json(getAs(a.owner(), "/dashboard?" + period).andExpect(status().isOk()));
			String db = json(getAs(b.owner(), "/dashboard?" + period).andExpect(status().isOk()));
			for (String path : new String[] { "$.totals", "$.daily", "$.comparison", "$.balances.totalBalanceCents",
					"$.balances.openingTotalCents", "$.balances.closingTotalCents", "$.balances.accounts[*].balanceCents",
					"$.balances.accounts[*].closingBalanceCents", "$.expenseByCategory[*].totalCents",
					"$.incomeByCategory[*].totalCents" }) {
				assertThat((Object) JsonPath.read(da, path)).as(period + " " + path).isEqualTo(JsonPath.read(db, path));
			}
		}
		// as parcelas de dezembro só aparecem no bloco "pending" de dezembro
		String december = json(getAs(a.owner(), "/dashboard?month=2026-12"));
		assertThat(number(december, "$.totals.expenseCents")).isZero();
		assertThat(number(december, "$.pending.expenseCents")).isEqualTo(102_000);
		assertThat(number(december, "$.pending.count")).isEqualTo(2);
		String november = json(getAs(a.owner(), "/dashboard?month=2026-11"));
		assertThat(number(november, "$.totals.expenseCents")).isEqualTo(102_000);
		assertThat(number(november, "$.pending.count")).isZero();
		assertThat(number(json(getAs(b.owner(), "/dashboard?month=2026-12")), "$.pending.count")).isZero();
	}

	@Test
	void theMonthlyAndWeeklyBudgetSpendingIgnoresPendingInstallments() throws Exception {
		InstallmentScenario a = withInstallments();
		InstallmentScenario b = onlyTheFirstOnes();

		for (String path : new String[] { "/monthly-budgets?month=2026-11", "/monthly-budgets?month=2026-12",
				"/monthly-budgets?month=2027-01", "/budgets?week=2026-W50" }) {
			String ba = json(getAs(a.owner(), path).andExpect(status().isOk()));
			String bb = json(getAs(b.owner(), path).andExpect(status().isOk()));
			same("$.totalSpentCents", ba, bb);
			same("$.spentOutsideCategoryLimitsCents", ba, bb);
			same("$.budgets[*].spentCents", ba, bb);
			same("$.budgets[*].remainingCents", ba, bb);
			same("$.budgets[*].status", ba, bb);
		}
		// novembro conta os 2000 efetivados de Alimentação; dezembro e a semana de 7/12 ainda nada
		assertThat(number(json(getAs(a.owner(), "/monthly-budgets?month=2026-11")), "$.budgets[0].spentCents"))
				.isEqualTo(2_000);
		assertThat(number(json(getAs(a.owner(), "/monthly-budgets?month=2026-12")), "$.budgets[0].spentCents")).isZero();
		assertThat(number(json(getAs(a.owner(), "/budgets?week=2026-W50")), "$.totalSpentCents")).isZero();
	}

	@Test
	void eachInstallmentOnACardFallsInTheInvoiceOfItsOwnMonthOnlyOnceCleared() throws Exception {
		InstallmentScenario a = withInstallments();
		InstallmentScenario b = onlyTheFirstOnes();

		for (String month : new String[] { "2024-11", "2024-12", "2025-01" }) {
			String ia = json(getAs(a.owner(), "/accounts/" + a.card() + "/invoices/" + month).andExpect(status().isOk()));
			String ib = json(getAs(b.owner(), "/accounts/" + b.card() + "/invoices/" + month).andExpect(status().isOk()));
			for (String path : new String[] { "$.totalCents", "$.paidCents", "$.remainingCents", "$.status",
					"$.lines[*].chargeCents" }) {
				same(path, ia, ib);
			}
		}
		assertThat(number(json(getAs(a.owner(), "/accounts/" + a.card() + "/invoices/2024-11")), "$.totalCents"))
				.isEqualTo(3_000);
		assertThat(number(json(getAs(a.owner(), "/accounts/" + a.card() + "/invoices/2024-12")), "$.totalCents")).isZero();

		String sa = json(getAs(a.owner(), "/accounts/" + a.card() + "/card-summary"));
		String sb = json(getAs(b.owner(), "/accounts/" + b.card() + "/card-summary"));
		same("$.owedCents", sa, sb);
		same("$.availableCents", sa, sb);
		assertThat(number(sa, "$.owedCents")).isEqualTo(3_000);
	}

	@Test
	void clearingEveryPendingInstallmentMakesThemCountInExactCents() throws Exception {
		InstallmentScenario a = withInstallments();
		List<UUID> ids = owner.client().sql("select id from transactions where workspace_id = :w and status = 'PENDING' "
				+ "and deleted_at is null").param("w", a.owner().workspaceId()).query(UUID.class).list();
		assertThat(ids).hasSize(6);

		for (UUID id : ids) {
			PendingScenario.clear(mvc, a.owner(), id).andExpect(status().isOk());
		}

		// 500000 - 3 x 100000 - 3 x 2000
		String accounts = json(getAs(a.owner(), "/accounts"));
		assertThat((List<?>) JsonPath.read(accounts, "$[?(@.name=='Corrente')].balanceCents")).isEqualTo(List.of(194_000));
		assertThat((List<?>) JsonPath.read(accounts, "$[?(@.name=='Corrente')].pendingCents")).isEqualTo(List.of(0));
		assertThat(number(json(getAs(a.owner(), "/dashboard?month=2026-12")), "$.totals.expenseCents")).isEqualTo(102_000);
		assertThat(number(json(getAs(a.owner(), "/dashboard?month=2027-01")), "$.totals.expenseCents")).isEqualTo(102_000);
		assertThat(number(json(getAs(a.owner(), "/monthly-budgets?month=2026-12")), "$.budgets[0].spentCents"))
				.isEqualTo(2_000);
		assertThat(number(json(getAs(a.owner(), "/monthly-budgets?month=2027-01")), "$.budgets[0].spentCents"))
				.isEqualTo(2_000);
		// a semana de 7/12 tem a parcela do Notebook (100000) e a do Mercado (2000): o total soma as duas categorias
		// e o limite de Alimentação só a do Mercado
		String week = json(getAs(a.owner(), "/budgets?week=2026-W50"));
		assertThat(number(week, "$.totalSpentCents")).isEqualTo(102_000);
		assertThat(number(week, "$.budgets[0].spentCents")).isEqualTo(2_000);
		for (String month : new String[] { "2024-11", "2024-12", "2025-01" }) {
			assertThat(number(json(getAs(a.owner(), "/accounts/" + a.card() + "/invoices/" + month)), "$.totalCents"))
					.as("fatura " + month).isEqualTo(3_000);
		}
	}

}
