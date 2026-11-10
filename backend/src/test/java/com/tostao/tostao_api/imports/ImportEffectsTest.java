package com.tostao.tostao_api.imports;

import static com.tostao.tostao_api.imports.ImportScenario.body;
import static com.tostao.tostao_api.imports.ImportScenario.confirm;
import static com.tostao.tostao_api.imports.ImportScenario.line;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * SC-006: os lançamentos importados são lançamentos comuns e efetivados. Entram, em exatos centavos, no saldo da conta, no
 * dashboard (o que não tem categoria no grupo "Sem categoria"), nos orçamentos semanal e mensal (sem categoria, só no
 * limite geral), na fatura do cartão e na listagem; excluir um tira de tudo; nada pendente é criado.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ImportEffectsTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private record World(ImportScenario s, UUID card) {
	}

	/** O cenário padrão mais um cartão (fecha dia 5, vence dia 12) e os limites de novembro. */
	private World world() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		ImportScenario s = ImportScenario.create(f);
		UUID w = s.workspace();
		UUID card = f.insertCard(w, "Cartão", 0, 1_000_000L, 5, 12, false);
		UUID u = s.owner().userId();
		f.insertMonthlyBudget(w, LocalDate.of(2026, 11, 1), null, 600_000, u);
		f.insertMonthlyBudget(w, LocalDate.of(2026, 11, 1), s.food(), 100_000, u);
		f.insertWeeklyBudget(w, LocalDate.of(2026, 11, 2), null, 300_000, u);
		f.insertWeeklyBudget(w, LocalDate.of(2026, 11, 2), s.food(), 100_000, u);
		return new World(s, card);
	}

	private static String json(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private String getAs(Fixture who, String path) throws Exception {
		return json(mvc.perform(get("/api/v1/workspaces/" + who.workspaceId() + path).cookie(session(who.token())))
				.andExpect(status().isOk()));
	}

	/** Salário sem categoria, despesa de alimentação com categoria e uma despesa sem categoria, todas em novembro. */
	private String importNovember(World w) throws Exception {
		ImportScenario s = w.s();
		return json(confirm(mvc, s.owner(), s.workspace(), s.checking(), body(
				line("n1", "2026-11-05", "Salário", 500_000, null),
				line("n2", "2026-11-05", "Mercado", -20_000, s.food()),
				line("n3", "2026-11-05", "PIX sem categoria", -30_000, null))).andExpect(status().isCreated()));
	}

	@Test
	void theBalanceTheListingAndNothingPending() throws Exception {
		World w = world();
		ImportScenario s = w.s();

		importNovember(w);

		String accounts = getAs(s.owner(), "/accounts");
		assertThat((List<Integer>) JsonPath.read(accounts, "$[?(@.name=='Corrente')].balanceCents")).containsExactly(550_000);
		assertThat((List<Integer>) JsonPath.read(accounts, "$[?(@.name=='Corrente')].pendingCents")).containsExactly(0);
		String listing = getAs(s.owner(), "/transactions?accountId=" + s.checking());
		assertThat((List<Boolean>) JsonPath.read(listing, "$.items[*].imported")).containsOnly(true).hasSize(3);
		assertThat(owner.client().sql("select count(*) from transactions where workspace_id = :w and status <> 'CLEARED'")
				.param("w", s.workspace()).query(Long.class).single()).isZero();
	}

	@Test
	void theDashboardPutsWhatHasNoCategoryInTheNoCategoryGroup() throws Exception {
		World w = world();
		ImportScenario s = w.s();

		importNovember(w);

		String dashboard = getAs(s.owner(), "/dashboard?month=2026-11");
		assertThat((Number) JsonPath.read(dashboard, "$.totals.incomeCents")).isEqualTo(500_000);
		assertThat((Number) JsonPath.read(dashboard, "$.totals.expenseCents")).isEqualTo(50_000);
		assertThat((Number) JsonPath.read(dashboard, "$.totals.resultCents")).isEqualTo(450_000);
		assertThat((List<Integer>) JsonPath.read(dashboard, "$.expenseByCategory[?(@.name=='Sem categoria')].totalCents")).containsExactly(30_000);
		assertThat((List<Object>) JsonPath.read(dashboard, "$.expenseByCategory[?(@.name=='Sem categoria')].categoryId")).containsOnlyNulls();
		assertThat((List<Integer>) JsonPath.read(dashboard, "$.expenseByCategory[?(@.name=='Alimentação')].totalCents")).containsExactly(20_000);
		assertThat((List<Integer>) JsonPath.read(dashboard, "$.incomeByCategory[?(@.name=='Sem categoria')].totalCents")).containsExactly(500_000);
		assertThat((Number) JsonPath.read(dashboard, "$.balances.totalBalanceCents")).isEqualTo(550_000);
		// semana ISO de 5 de novembro de 2026: 2026-W45
		String week = getAs(s.owner(), "/dashboard?week=2026-W45");
		assertThat((Number) JsonPath.read(week, "$.totals.expenseCents")).isEqualTo(50_000);
	}

	@Test
	void anUncategorizedExpenseCountsOnlyInTheGeneralLimitOfBothBudgets() throws Exception {
		World w = world();
		ImportScenario s = w.s();

		importNovember(w);

		String month = getAs(s.owner(), "/monthly-budgets?month=2026-11");
		assertThat((Number) JsonPath.read(month, "$.totalSpentCents")).isEqualTo(50_000);
		assertThat((Number) JsonPath.read(month, "$.spentOutsideCategoryLimitsCents")).isEqualTo(30_000);
		assertThat((List<Integer>) JsonPath.read(month, "$.budgets[?(@.categoryId==null)].spentCents")).containsExactly(50_000);
		assertThat((List<Integer>) JsonPath.read(month, "$.budgets[?(@.categoryName=='Alimentação')].spentCents")).containsExactly(20_000);
		String week = getAs(s.owner(), "/budgets?week=2026-W45");
		assertThat((Number) JsonPath.read(week, "$.totalSpentCents")).isEqualTo(50_000);
		assertThat((Number) JsonPath.read(week, "$.spentOutsideCategoryLimitsCents")).isEqualTo(30_000);
		assertThat((List<Integer>) JsonPath.read(week, "$.budgets[?(@.categoryName=='Alimentação')].spentCents")).containsExactly(20_000);
	}

	@Test
	void importedCardPurchasesComposeTheInvoiceAndTheCardSummary() throws Exception {
		World w = world();
		ImportScenario s = w.s();

		// fecha dia 5: a compra de 10/10 cai na fatura que fecha em 05/11 e vence em 12/11
		confirm(mvc, s.owner(), s.workspace(), w.card(), body(
				line("c1", "2026-10-10", "Restaurante", -8_990, null),
				line("c2", "2026-10-20", "Loja", -125_000, s.food()))).andExpect(status().isCreated());

		String invoice = getAs(s.owner(), "/accounts/" + w.card() + "/invoices/2026-11");
		assertThat((Number) JsonPath.read(invoice, "$.totalCents")).isEqualTo(133_990);
		assertThat((List<?>) JsonPath.read(invoice, "$.lines")).hasSize(2);
		String summary = getAs(s.owner(), "/accounts/" + w.card() + "/card-summary");
		assertThat((Number) JsonPath.read(summary, "$.owedCents")).isEqualTo(133_990);
	}

	@Test
	void deletingAnImportedTransactionRemovesItFromEverything() throws Exception {
		World w = world();
		ImportScenario s = w.s();
		String created = importNovember(w);
		String uncategorized = JsonPath.read(created, "$.created[2].id");

		mvc.perform(delete("/api/v1/workspaces/" + s.workspace() + "/transactions/" + uncategorized).cookie(session(s.owner().token())))
				.andExpect(status().isNoContent());

		String accounts = getAs(s.owner(), "/accounts");
		assertThat((List<Integer>) JsonPath.read(accounts, "$[?(@.name=='Corrente')].balanceCents")).containsExactly(580_000);
		String dashboard = getAs(s.owner(), "/dashboard?month=2026-11");
		assertThat((Number) JsonPath.read(dashboard, "$.totals.expenseCents")).isEqualTo(20_000);
		assertThat((List<?>) JsonPath.read(dashboard, "$.expenseByCategory[?(@.name=='Sem categoria')]")).isEmpty();
		String month = getAs(s.owner(), "/monthly-budgets?month=2026-11");
		assertThat((Number) JsonPath.read(month, "$.spentOutsideCategoryLimitsCents")).isEqualTo(0);
	}

	@Test
	void anImportedLineIsExactToTheCentWithLargeAndSmallValues() throws Exception {
		World w = world();
		ImportScenario s = w.s();

		confirm(mvc, s.owner(), s.workspace(), s.checking(), body(
				line("g", "2026-11-05", "Grande", 999_999_999_999L, null),
				line("p", "2026-11-05", "Pequena", -1, null),
				line("q", "2026-11-05", "Quase", -999_999_999_999L, null))).andExpect(status().isCreated());

		String accounts = getAs(s.owner(), "/accounts");
		// 100.000 inicial + 999.999.999.999 - 1 - 999.999.999.999
		List<Number> balances = JsonPath.read(accounts, "$[?(@.name=='Corrente')].balanceCents");
		assertThat(balances).singleElement().satisfies(balance -> assertThat(balance.longValue()).isEqualTo(99_999L));
	}

}
