package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.month;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** O estado, o percentual e a ordem dos limites do mês (as mesmas regras do orçamento semanal). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MonthlyBudgetStatusTest {

	private static final LocalDate NOV = LocalDate.of(2026, 11, 1);

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	/** Uma categoria de despesa com limite no mês e, se {@code spent > 0}, uma despesa dentro dele. */
	private UUID limitWithSpending(Fixture fx, UUID account, String name, long limit, long spent) {
		UUID category = fixtures().insertCategory(fx.workspaceId(), null, name, "EXPENSE", false);
		fixtures().insertMonthlyBudget(fx.workspaceId(), NOV, category, limit, fx.userId());
		if (spent > 0) {
			fixtures().insertLedgerTransaction(fx.workspaceId(), account, fx.userId(), category, "EXPENSE", spent,
					LocalDate.of(2026, 11, 15), "Gasto de " + name, false);
		}
		return category;
	}

	@Test
	void theExactThresholdsGiveWithinWarningWarningAndExceeded() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		limitWithSpending(fx, account, "A 79,99", 10_000, 7_999);
		limitWithSpending(fx, account, "B 80", 10_000, 8_000);
		limitWithSpending(fx, account, "C 100", 10_000, 10_000);
		limitWithSpending(fx, account, "D 100,01", 10_000, 10_001);

		month(mvc, fx, "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='A 79,99')].status").value("WITHIN"))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='A 79,99')].percentUsed").value(79.99))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='B 80')].status").value("WARNING"))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='B 80')].percentUsed").value(80.0))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='C 100')].status").value("WARNING"))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='C 100')].percentUsed").value(100.0))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='C 100')].remainingCents").value(0))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='D 100,01')].status").value("EXCEEDED"))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='D 100,01')].percentUsed").value(100.01))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='D 100,01')].remainingCents").value(-1));
	}

	@Test
	void theStateUsesTheExactCentsNotTheRoundedPercentage() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		// 79,999% arredonda para 80,00, mas continua abaixo de 80%
		limitWithSpending(fx, account, "Quase", 100_000, 79_999);
		limitWithSpending(fx, account, "Exato", 100_000, 80_000);

		month(mvc, fx, "month=2026-11")
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Quase')].percentUsed").value(80.0))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Quase')].status").value("WITHIN"))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Exato')].status").value("WARNING"));
	}

	@Test
	void aZeroLimitIsWithinWithoutSpendingAndExceededWithAnyAndHasNoPercentage() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		limitWithSpending(fx, account, "Sem gasto", 0, 0);
		limitWithSpending(fx, account, "Com gasto", 0, 1);

		month(mvc, fx, "month=2026-11")
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Sem gasto')].status").value("WITHIN"))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Sem gasto')].percentUsed").value((Object) null))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Com gasto')].status").value("EXCEEDED"))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Com gasto')].percentUsed").value((Object) null))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Com gasto')].remainingCents").value(-1));
	}

	@Test
	void limitsComeExceededThenWarningThenWithinThenBiggerSpending() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		limitWithSpending(fx, account, "Dentro", 10_000, 1_000);
		limitWithSpending(fx, account, "Atenção pequena", 10_000, 8_000);
		limitWithSpending(fx, account, "Atenção grande", 10_000, 9_500);
		limitWithSpending(fx, account, "Estourado", 10_000, 12_000);

		month(mvc, fx, "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.budgets[*].categoryName").value(org.hamcrest.Matchers.contains("Estourado",
						"Atenção grande", "Atenção pequena", "Dentro")));
	}

	@Test
	void tiesAreBrokenByNameWithoutCaseAndTheGeneralLimitComesFirst() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		limitWithSpending(fx, account, "banana", 10_000, 0);
		limitWithSpending(fx, account, "Abacaxi", 10_000, 0);
		fixtures().insertMonthlyBudget(fx.workspaceId(), NOV, null, 10_000, fx.userId());

		// todos "dentro do limite" com gasto zero: só o nome desempata, e o geral (sem nome) vem antes
		month(mvc, fx, "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.budgets[0].categoryId").doesNotExist())
				.andExpect(jsonPath("$.budgets[1].categoryName").value("Abacaxi"))
				.andExpect(jsonPath("$.budgets[2].categoryName").value("banana"));
	}

}
