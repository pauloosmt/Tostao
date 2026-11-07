package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.BudgetScenario.week;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
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

/** SC-003: o estado e o percentual de cada limite, com os centavos exatos nos limiares de 80% e 100%. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BudgetStatusTest {

	/** Segunda-feira de 2026-W10; cada caso usa uma semana própria a partir dela. */
	private static final LocalDate BASE = LocalDate.of(2026, 3, 2);

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private String track(Fixture who, LocalDate monday) throws Exception {
		String id = com.tostao.tostao_api.dashboard.Period.containing(monday).id();
		return week(mvc, who, "week=" + id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	/** Um limite geral de {@code limit} na semana {@code offset} e uma despesa de {@code spent} nela. */
	private void limitAndSpending(WorkspaceFixtures fixtures, BudgetScenario s, int offset, long limit, long spent) {
		UUID w = s.owner().workspaceId();
		LocalDate monday = BASE.plusWeeks(offset);
		fixtures.insertWeeklyBudget(w, monday, null, limit, s.owner().userId());
		if (spent > 0) {
			fixtures.insertLedgerTransaction(w, s.checking(), s.owner().userId(), s.food(), "EXPENSE", spent,
					monday.plusDays(2), "gasto", false);
		}
	}

	@Test
	void theThresholdsAt80And100PercentAreDecidedWithExactCents() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		long[][] cases = { { 7_999, 79_99 }, { 8_000, 80_00 }, { 10_000, 100_00 }, { 10_001, 100_01 } };
		String[] expected = { "WITHIN", "WARNING", "WARNING", "EXCEEDED" };
		for (int i = 0; i < cases.length; i++) {
			limitAndSpending(fixtures, s, i, 10_000, cases[i][0]);
		}

		for (int i = 0; i < cases.length; i++) {
			String json = track(s.owner(), BASE.plusWeeks(i));
			assertThat(JsonPath.<String>read(json, "$.budgets[0].status")).as("gasto " + cases[i][0]).isEqualTo(expected[i]);
			assertThat(new BigDecimal(String.valueOf(JsonPath.<Number>read(json, "$.budgets[0].percentUsed"))))
					.isEqualByComparingTo(BigDecimal.valueOf(cases[i][1], 2));
		}
	}

	@Test
	void theRoundedPercentageNeverDecidesTheStatus() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		limitAndSpending(fixtures, s, 0, 100_000, 79_999);
		limitAndSpending(fixtures, s, 1, 100_000, 100_004);

		String below = track(s.owner(), BASE);
		String over = track(s.owner(), BASE.plusWeeks(1));

		// 79,999% aparece como 80.00, mas ainda é "dentro do limite"
		assertThat(JsonPath.<Number>read(below, "$.budgets[0].percentUsed").doubleValue()).isEqualTo(80.0);
		assertThat(JsonPath.<String>read(below, "$.budgets[0].status")).isEqualTo("WITHIN");
		// 100,004% aparece como 100.00, mas já estourou
		assertThat(JsonPath.<Number>read(over, "$.budgets[0].percentUsed").doubleValue()).isEqualTo(100.0);
		assertThat(JsonPath.<String>read(over, "$.budgets[0].status")).isEqualTo("EXCEEDED");
		assertThat(JsonPath.<Number>read(over, "$.budgets[0].remainingCents").longValue()).isEqualTo(-4);
	}

	@Test
	void percentagesHaveTwoDecimalsRoundedHalfUp() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		limitAndSpending(fixtures, s, 0, 3, 1);
		limitAndSpending(fixtures, s, 1, 3, 2);
		limitAndSpending(fixtures, s, 2, 8, 5);

		assertThat(JsonPath.<Number>read(track(s.owner(), BASE), "$.budgets[0].percentUsed").doubleValue())
				.isEqualTo(33.33);
		assertThat(JsonPath.<Number>read(track(s.owner(), BASE.plusWeeks(1)), "$.budgets[0].percentUsed").doubleValue())
				.isEqualTo(66.67);
		assertThat(JsonPath.<Number>read(track(s.owner(), BASE.plusWeeks(2)), "$.budgets[0].percentUsed").doubleValue())
				.isEqualTo(62.5);
	}

	@Test
	void limitsAreOrderedByStatusThenSpendingThenNameThenId() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		UUID u = s.owner().userId();
		UUID beta = fixtures.insertCategory(w, null, "beta", "EXPENSE", false);
		UUID alfa = fixtures.insertCategory(w, null, "Alfa", "EXPENSE", false);
		UUID gama = fixtures.insertCategory(w, null, "Gama", "EXPENSE", false);
		UUID delta = fixtures.insertCategory(w, null, "Delta", "EXPENSE", false);
		LocalDate monday = BASE;
		// dois estourados com o mesmo gasto (desempata por nome), um em atenção, dois dentro do limite
		for (UUID[] row : new UUID[][] { { beta }, { alfa } }) {
			fixtures.insertWeeklyBudget(w, monday, row[0], 100, u);
			fixtures.insertLedgerTransaction(w, s.checking(), u, row[0], "EXPENSE", 500, monday, "estourou", false);
		}
		fixtures.insertWeeklyBudget(w, monday, gama, 1_000, u);
		fixtures.insertLedgerTransaction(w, s.checking(), u, gama, "EXPENSE", 900, monday, "atenção", false);
		fixtures.insertWeeklyBudget(w, monday, delta, 10_000, u);
		fixtures.insertLedgerTransaction(w, s.checking(), u, delta, "EXPENSE", 100, monday, "dentro", false);
		fixtures.insertWeeklyBudget(w, monday, null, 100_000, u);
		fixtures.insertWeeklyBudget(w, monday, s.leisure(), 50_000, u);
		fixtures.insertLedgerTransaction(w, s.checking(), u, s.leisure(), "EXPENSE", 100, monday, "dentro", false);

		String json = track(s.owner(), monday);

		List<String> names = JsonPath.read(json, "$.budgets[*].categoryName");
		List<String> status = JsonPath.read(json, "$.budgets[*].status");
		// estourados: Alfa e beta (gasto igual, nome sem diferenciar maiúsculas); atenção: Gama; dentro do limite:
		// maior gasto primeiro (o geral soma tudo: 2.100); Delta e Lazer têm 100 e desempatam pelo nome
		assertThat(names).containsExactly("Alfa", "beta", "Gama", null, "Delta", "Lazer");
		assertThat(status).containsExactly("EXCEEDED", "EXCEEDED", "WARNING", "WITHIN", "WITHIN", "WITHIN");
	}

}
