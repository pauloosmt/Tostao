package com.tostao.tostao_api.dashboard;

import static com.tostao.tostao_api.dashboard.DashboardScenario.dashboard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

/** US2: despesas e receitas do mês agrupadas por categoria principal, com subcategorias e percentuais. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CategoryGroupsTest {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

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

	/** Workspace vazio com uma conta; os testes criam as categorias e os lançamentos de que precisam. */
	private record World(WorkspaceFixtures fx, Fixture owner, UUID account) {

		UUID category(UUID parent, String name, String kind, boolean archived) {
			return fx.insertCategory(owner.workspaceId(), parent, name, kind, archived);
		}

		void ledger(UUID category, String type, long cents) {
			fx.insertLedgerTransaction(owner.workspaceId(), account, owner.userId(), category, type, cents, DAY, "Teste",
					false);
		}

	}

	private World world() {
		WorkspaceFixtures fx = fixtures();
		Fixture owner = fx.workspaceWithOwner();
		UUID account = fx.insertAccount(owner.workspaceId(), "Corrente", "CHECKING", 0, false);
		return new World(fx, owner, account);
	}

	@Test
	void theScenarioGroupsFoodWithItsSubcategoryAndSalaryAsIncome() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		dashboard(mvc, s.owner(), "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.expenseByCategory.length()").value(1))
				.andExpect(jsonPath("$.expenseByCategory[0].categoryId").value(s.food().toString()))
				.andExpect(jsonPath("$.expenseByCategory[0].name").value("Alimentação"))
				.andExpect(jsonPath("$.expenseByCategory[0].directCents").value(10000))
				.andExpect(jsonPath("$.expenseByCategory[0].totalCents").value(15000))
				.andExpect(jsonPath("$.expenseByCategory[0].percent").value(100.00))
				.andExpect(jsonPath("$.expenseByCategory[0].children.length()").value(1))
				.andExpect(jsonPath("$.expenseByCategory[0].children[0].categoryId").value(s.market().toString()))
				.andExpect(jsonPath("$.expenseByCategory[0].children[0].name").value("Mercado"))
				.andExpect(jsonPath("$.expenseByCategory[0].children[0].totalCents").value(5000))
				.andExpect(jsonPath("$.expenseByCategory[0].children[0].percent").value(33.33))
				.andExpect(jsonPath("$.incomeByCategory.length()").value(1))
				.andExpect(jsonPath("$.incomeByCategory[0].name").value("Salário"))
				.andExpect(jsonPath("$.incomeByCategory[0].totalCents").value(250000))
				.andExpect(jsonPath("$.incomeByCategory[0].percent").value(100.00))
				.andExpect(jsonPath("$.incomeByCategory[0].children.length()").value(0));
	}

	@Test
	void groupsComeFromTheBiggestToTheSmallestWithPercentages() throws Exception {
		World w = world();
		UUID food = w.category(null, "Alimentação", "EXPENSE", false);
		UUID transport = w.category(null, "Transporte", "EXPENSE", false);
		UUID fun = w.category(null, "Lazer", "EXPENSE", false);
		w.ledger(fun, "EXPENSE", 1_000);
		w.ledger(food, "EXPENSE", 6_000);
		w.ledger(transport, "EXPENSE", 3_000);

		String json = body(w.owner(), "month=2026-11");

		assertThat(JsonPath.<List<String>>read(json, "$.expenseByCategory[*].name"))
				.containsExactly("Alimentação", "Transporte", "Lazer");
		assertThat(JsonPath.<List<Double>>read(json, "$.expenseByCategory[*].percent"))
				.containsExactly(60.00, 30.00, 10.00);
		assertThat(JsonPath.<List<?>>read(json, "$.incomeByCategory")).isEmpty();
	}

	@Test
	void equalTotalsAreOrderedByNameIgnoringCase() throws Exception {
		World w = world();
		UUID zeta = w.category(null, "zeta", "EXPENSE", false);
		UUID alpha = w.category(null, "Alfa", "EXPENSE", false);
		UUID beta = w.category(null, "beta", "EXPENSE", false);
		w.ledger(zeta, "EXPENSE", 100);
		w.ledger(alpha, "EXPENSE", 100);
		w.ledger(beta, "EXPENSE", 100);

		assertThat(JsonPath.<List<String>>read(body(w.owner(), "month=2026-11"), "$.expenseByCategory[*].name"))
				.containsExactly("Alfa", "beta", "zeta");
	}

	@Test
	void categoriesWithoutTransactionsInThePeriodDoNotAppear() throws Exception {
		World w = world();
		UUID used = w.category(null, "Usada", "EXPENSE", false);
		w.category(null, "Sem uso", "EXPENSE", false);
		w.ledger(used, "EXPENSE", 500);
		w.fx().insertLedgerTransaction(w.owner().workspaceId(), w.account(), w.owner().userId(),
				w.category(null, "De outro mês", "EXPENSE", false), "EXPENSE", 900, LocalDate.of(2026, 10, 10), "Antigo",
				false);

		dashboard(mvc, w.owner(), "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.expenseByCategory.length()").value(1))
				.andExpect(jsonPath("$.expenseByCategory[0].name").value("Usada"));
	}

	@Test
	void archivedCategoriesAndSubcategoriesStillAppear() throws Exception {
		World w = world();
		UUID parent = w.category(null, "Casa", "EXPENSE", false);
		UUID archivedChild = w.category(parent, "Reforma", "EXPENSE", true);
		UUID archivedRoot = w.category(null, "Velha", "EXPENSE", true);
		w.ledger(archivedChild, "EXPENSE", 4_000);
		w.ledger(archivedRoot, "EXPENSE", 1_000);

		dashboard(mvc, w.owner(), "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.expenseByCategory[0].name").value("Casa"))
				.andExpect(jsonPath("$.expenseByCategory[0].totalCents").value(4000))
				.andExpect(jsonPath("$.expenseByCategory[0].directCents").value(0))
				.andExpect(jsonPath("$.expenseByCategory[0].children[0].name").value("Reforma"))
				.andExpect(jsonPath("$.expenseByCategory[1].name").value("Velha"));
	}

	@Test
	void aMainCategoryWithoutSubcategoryUsageHasNoChildren() throws Exception {
		World w = world();
		UUID parent = w.category(null, "Casa", "EXPENSE", false);
		w.category(parent, "Reforma", "EXPENSE", false);
		w.ledger(parent, "EXPENSE", 2_500);

		dashboard(mvc, w.owner(), "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.expenseByCategory[0].directCents").value(2500))
				.andExpect(jsonPath("$.expenseByCategory[0].totalCents").value(2500))
				.andExpect(jsonPath("$.expenseByCategory[0].children.length()").value(0));
	}

	@Test
	void uncategorizedTransactionsFormTheNoCategoryGroup() throws Exception {
		World w = world();
		UUID food = w.category(null, "Alimentação", "EXPENSE", false);
		w.ledger(food, "EXPENSE", 7_500);
		w.ledger(null, "EXPENSE", 2_500);
		w.ledger(null, "INCOME", 1_000);

		String json = body(w.owner(), "month=2026-11");

		assertThat(JsonPath.<String>read(json, "$.expenseByCategory[1].name")).isEqualTo("Sem categoria");
		assertThat(JsonPath.<Object>read(json, "$.expenseByCategory[1].categoryId")).isNull();
		assertThat(number(json, "$.expenseByCategory[1].totalCents")).isEqualTo(2_500);
		assertThat(JsonPath.<List<?>>read(json, "$.expenseByCategory[1].children")).isEmpty();
		assertThat(number(json, "$.totals.expenseCents")).isEqualTo(10_000);
		assertThat(JsonPath.<Double>read(json, "$.expenseByCategory[1].percent")).isEqualTo(25.00);
		assertThat(JsonPath.<String>read(json, "$.incomeByCategory[0].name")).isEqualTo("Sem categoria");
		assertThat(JsonPath.<Double>read(json, "$.incomeByCategory[0].percent")).isEqualTo(100.00);
	}

	@Test
	void groupTotalsAddUpToTheTypeTotalAndDirectPlusChildrenToTheGroupTotal() throws Exception {
		World w = world();
		UUID food = w.category(null, "Alimentação", "EXPENSE", false);
		UUID market = w.category(food, "Mercado", "EXPENSE", false);
		UUID restaurant = w.category(food, "Restaurante", "EXPENSE", false);
		UUID home = w.category(null, "Casa", "EXPENSE", false);
		UUID pay = w.category(null, "Salário", "INCOME", false);
		w.ledger(food, "EXPENSE", 1_001);
		w.ledger(market, "EXPENSE", 2_002);
		w.ledger(restaurant, "EXPENSE", 3_003);
		w.ledger(home, "EXPENSE", 4_004);
		w.ledger(null, "EXPENSE", 5);
		w.ledger(pay, "INCOME", 9_999);

		String json = body(w.owner(), "month=2026-11");

		long groupsTotal = ((List<?>) JsonPath.read(json, "$.expenseByCategory[*].totalCents")).stream()
				.mapToLong(v -> ((Number) v).longValue()).sum();
		assertThat(groupsTotal).isEqualTo(number(json, "$.totals.expenseCents")).isEqualTo(10_015);
		assertThat(JsonPath.<List<Integer>>read(json, "$.expenseByCategory[?(@.name=='Alimentação')].totalCents"))
				.containsExactly(6_006);
		assertThat(JsonPath.<List<Integer>>read(json, "$.expenseByCategory[?(@.name=='Alimentação')].directCents"))
				.containsExactly(1_001);
		assertThat(number(json, "$.incomeByCategory[0].totalCents")).isEqualTo(9_999);
	}

	@Test
	void aMonthWithoutExpensesHasNoExpenseGroupsAndNoDivisionByZero() throws Exception {
		World w = world();
		UUID pay = w.category(null, "Salário", "INCOME", false);
		w.ledger(pay, "INCOME", 1_000);

		dashboard(mvc, w.owner(), "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.expenseByCategory.length()").value(0))
				.andExpect(jsonPath("$.incomeByCategory.length()").value(1));
	}

	@Test
	void transfersAndDeletedTransactionsNeverBecomeGroups() throws Exception {
		World w = world();
		UUID other = w.fx().insertAccount(w.owner().workspaceId(), "Poupança", "SAVINGS", 0, false);
		UUID food = w.category(null, "Alimentação", "EXPENSE", false);
		w.ledger(food, "EXPENSE", 100);
		w.fx().insertTransferPair(w.owner().workspaceId(), w.account(), other, w.owner().userId(), 50_000, DAY,
				"Reserva");
		w.fx().insertLedgerTransaction(w.owner().workspaceId(), w.account(), w.owner().userId(),
				w.category(null, "Excluída", "EXPENSE", false), "EXPENSE", 777, DAY, "Engano", true);

		dashboard(mvc, w.owner(), "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.expenseByCategory.length()").value(1))
				.andExpect(jsonPath("$.expenseByCategory[0].name").value("Alimentação"))
				.andExpect(jsonPath("$.incomeByCategory.length()").value(0));
	}

}
