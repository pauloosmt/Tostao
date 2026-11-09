package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.body;
import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.copy;
import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.define;
import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.month;
import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.remove;
import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.update;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * SC-002, SC-013 e FR-023: o orçamento mensal só lê lançamentos, contas e categorias; mexer neles nunca altera
 * um limite; e os orçamentos mensal e semanal não se tocam.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MonthlyBudgetUntouchedDataTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private String balance(Fixture who) throws Exception {
		return mvc.perform(get("/api/v1/workspaces/" + who.workspaceId() + "/reports/balance")
				.cookie(session(who.token()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	private String transactionsUrl(Fixture who) {
		return "/api/v1/workspaces/" + who.workspaceId() + "/transactions";
	}

	private long totalSpent(Fixture who) throws Exception {
		String json = month(mvc, who, "month=2026-11").andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		return ((Number) JsonPath.read(json, "$.totalSpentCents")).longValue();
	}

	@Test
	void everyMonthlyBudgetOperationLeavesFinanceDataWeeklyLimitsAndBalancesUntouched() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		fixtures().insertWeeklyBudget(w, java.time.LocalDate.of(2026, 11, 2), s.food(), 16_000, s.owner().userId());
		String financeBefore = MonthlyBudgetScenario.financeFingerprint(owner, w);
		String weeklyBefore = MonthlyBudgetScenario.weeklyBudgetsFingerprint(owner, w);
		String balanceBefore = balance(s.owner());

		String created = define(mvc, s.owner(), body("2026-11", s.food(), 64_000)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");
		define(mvc, s.owner(), body("2026-11", s.food(), 70_000)).andExpect(status().isOk());
		define(mvc, s.owner(), body("2026-11", null, 400_000)).andExpect(status().isCreated());
		update(mvc, w, s.owner(), id, "{\"amountCents\":72000}").andExpect(status().isOk());
		copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":3}").andExpect(status().isOk());
		month(mvc, s.owner(), "month=2026-11").andExpect(status().isOk());
		remove(mvc, w, s.owner(), id).andExpect(status().isNoContent());

		assertThat(MonthlyBudgetScenario.financeFingerprint(owner, w)).isEqualTo(financeBefore);
		assertThat(MonthlyBudgetScenario.weeklyBudgetsFingerprint(owner, w)).isEqualTo(weeklyBefore);
		assertThat(balance(s.owner())).isEqualTo(balanceBefore);
	}

	@Test
	void everyWeeklyBudgetOperationLeavesTheMonthlyLimitsUntouched() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		s.seedQuickstartBudgets(fixtures());
		UUID w = s.owner().workspaceId();
		String monthlyBefore = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w);

		String created = BudgetScenario.define(mvc, s.owner(), BudgetScenario.body("2026-W45", s.food(), 16_000))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");
		BudgetScenario.define(mvc, s.owner(), BudgetScenario.body("2026-W45", s.food(), 17_000)).andExpect(status().isOk());
		BudgetScenario.update(mvc, w, s.owner(), id, "{\"amountCents\":18000}").andExpect(status().isOk());
		BudgetScenario.copy(mvc, w, s.owner(), "{\"fromWeek\":\"2026-W45\",\"weeks\":4}").andExpect(status().isOk());
		BudgetScenario.week(mvc, s.owner(), "week=2026-W45").andExpect(status().isOk());
		BudgetScenario.remove(mvc, w, s.owner(), id).andExpect(status().isNoContent());

		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w)).isEqualTo(monthlyBefore);
		assertThat(fixtures().countMonthlyBudgets(w)).isEqualTo(4);
	}

	@Test
	void changingTransactionsChangesTheSpendingButNeverALimit() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		s.seedQuickstartBudgets(fixtures());
		UUID w = s.owner().workspaceId();
		String limitsBefore = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w);
		assertThat(totalSpent(s.owner())).isEqualTo(104_000);

		// despesa nova no mês: o gasto sobe
		String expense = mvc.perform(post(transactionsUrl(s.owner())).cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"type":"EXPENSE","accountId":"%s","categoryId":"%s","amountCents":1234,
						 "occurredOn":"2026-11-03","description":"Nova"}""".formatted(s.checking(), s.food())))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String expenseId = JsonPath.read(expense, "$.id");
		assertThat(totalSpent(s.owner())).isEqualTo(105_234);

		// receita e transferência no mês: o gasto não muda
		mvc.perform(post(transactionsUrl(s.owner())).cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"type":"INCOME","accountId":"%s","categoryId":"%s","amountCents":99999,
						 "occurredOn":"2026-11-03","description":"Ganho"}""".formatted(s.checking(), s.salary())))
				.andExpect(status().isCreated());
		mvc.perform(post(transactionsUrl(s.owner())).cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"type":"TRANSFER","accountId":"%s","destinationAccountId":"%s","amountCents":5000,
						 "occurredOn":"2026-11-04","description":"Reserva"}""".formatted(s.checking(), s.savings())))
				.andExpect(status().isCreated());
		assertThat(totalSpent(s.owner())).isEqualTo(105_234);

		// editar para outro mês tira o gasto deste; voltar devolve
		String edit = """
				{"accountId":"%s","categoryId":"%s","amountCents":1234,"occurredOn":"%s","description":"Nova"}""";
		mvc.perform(put(transactionsUrl(s.owner()) + "/" + expenseId).cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON).content(edit.formatted(s.checking(), s.food(), "2026-12-10")))
				.andExpect(status().isOk());
		assertThat(totalSpent(s.owner())).isEqualTo(104_000);
		mvc.perform(put(transactionsUrl(s.owner()) + "/" + expenseId).cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON).content(edit.formatted(s.checking(), s.food(), "2026-11-28")))
				.andExpect(status().isOk());
		assertThat(totalSpent(s.owner())).isEqualTo(105_234);

		// excluir tira o gasto
		mvc.perform(delete(transactionsUrl(s.owner()) + "/" + expenseId).cookie(session(s.owner().token())))
				.andExpect(status().isNoContent());
		assertThat(totalSpent(s.owner())).isEqualTo(104_000);

		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w)).isEqualTo(limitsBefore);
	}

	@Test
	void archivingACategoryOrAnAccountDoesNotChangeLimitsOrSpending() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		s.seedQuickstartBudgets(fixtures());
		UUID w = s.owner().workspaceId();
		String limitsBefore = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w);

		mvc.perform(post("/api/v1/workspaces/" + w + "/categories/" + s.transport() + "/archive")
				.cookie(session(s.owner().token()))).andExpect(status().isOk());
		mvc.perform(post("/api/v1/workspaces/" + w + "/accounts/" + s.checking() + "/archive")
				.cookie(session(s.owner().token()))).andExpect(status().isOk());

		assertThat(totalSpent(s.owner())).isEqualTo(104_000);
		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w)).isEqualTo(limitsBefore);
		month(mvc, s.owner(), "month=2026-11").andExpect(status().isOk());
	}

}
