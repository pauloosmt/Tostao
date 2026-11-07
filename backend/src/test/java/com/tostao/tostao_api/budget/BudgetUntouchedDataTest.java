package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.BudgetScenario.base;
import static com.tostao.tostao_api.budget.BudgetScenario.body;
import static com.tostao.tostao_api.budget.BudgetScenario.copy;
import static com.tostao.tostao_api.budget.BudgetScenario.define;
import static com.tostao.tostao_api.budget.BudgetScenario.remove;
import static com.tostao.tostao_api.budget.BudgetScenario.update;
import static com.tostao.tostao_api.budget.BudgetScenario.week;
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
 * SC-002 e SC-013: o orçamento só lê lançamentos, contas e categorias, e mexer neles nunca altera um limite.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BudgetUntouchedDataTest {

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
		String json = week(mvc, who, "week=2026-W45").andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		return ((Number) JsonPath.read(json, "$.totalSpentCents")).longValue();
	}

	@Test
	void everyBudgetOperationLeavesTransactionsAccountsCategoriesAndBalancesUntouched() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		String financeBefore = BudgetScenario.financeFingerprint(owner, w);
		String balanceBefore = balance(s.owner());

		String created = define(mvc, s.owner(), body("2026-W45", s.food(), 16_000)).andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(created, "$.id");
		define(mvc, s.owner(), body("2026-W45", s.food(), 17_000)).andExpect(status().isOk());
		define(mvc, s.owner(), body("2026-W45", null, 100_000)).andExpect(status().isCreated());
		update(mvc, w, s.owner(), id, "{\"amountCents\":18000}").andExpect(status().isOk());
		copy(mvc, w, s.owner(), "{\"fromWeek\":\"2026-W45\",\"weeks\":3}").andExpect(status().isOk());
		week(mvc, s.owner(), "week=2026-W45").andExpect(status().isOk());
		remove(mvc, w, s.owner(), id).andExpect(status().isNoContent());

		assertThat(BudgetScenario.financeFingerprint(owner, w)).isEqualTo(financeBefore);
		assertThat(balance(s.owner())).isEqualTo(balanceBefore);
	}

	@Test
	void changingTransactionsChangesTheSpendingButNeverALimit() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);
		UUID w = s.owner().workspaceId();
		String limitsBefore = BudgetScenario.budgetsFingerprint(owner, w);
		assertThat(totalSpent(s.owner())).isEqualTo(26_000);

		// despesa nova na semana: o gasto sobe
		String expense = mvc.perform(post(transactionsUrl(s.owner())).cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"type":"EXPENSE","accountId":"%s","categoryId":"%s","amountCents":1234,
						 "occurredOn":"2026-11-03","description":"Nova"}""".formatted(s.checking(), s.food())))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String expenseId = JsonPath.read(expense, "$.id");
		assertThat(totalSpent(s.owner())).isEqualTo(27_234);

		// receita e transferência na semana: o gasto não muda
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
		assertThat(totalSpent(s.owner())).isEqualTo(27_234);

		// editar para outra semana tira o gasto desta; voltar devolve
		String edit = """
				{"accountId":"%s","categoryId":"%s","amountCents":1234,"occurredOn":"%s","description":"Nova"}""";
		mvc.perform(put(transactionsUrl(s.owner()) + "/" + expenseId).cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON).content(edit.formatted(s.checking(), s.food(), "2026-11-10")))
				.andExpect(status().isOk());
		assertThat(totalSpent(s.owner())).isEqualTo(26_000);
		mvc.perform(put(transactionsUrl(s.owner()) + "/" + expenseId).cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON).content(edit.formatted(s.checking(), s.food(), "2026-11-08")))
				.andExpect(status().isOk());
		assertThat(totalSpent(s.owner())).isEqualTo(27_234);

		// excluir tira o gasto
		mvc.perform(delete(transactionsUrl(s.owner()) + "/" + expenseId).cookie(session(s.owner().token())))
				.andExpect(status().isNoContent());
		assertThat(totalSpent(s.owner())).isEqualTo(26_000);

		assertThat(BudgetScenario.budgetsFingerprint(owner, w)).isEqualTo(limitsBefore);
	}

	@Test
	void archivingACategoryOrAnAccountDoesNotChangeLimitsOrSpending() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);
		UUID w = s.owner().workspaceId();
		String limitsBefore = BudgetScenario.budgetsFingerprint(owner, w);

		mvc.perform(post("/api/v1/workspaces/" + w + "/categories/" + s.transport() + "/archive")
				.cookie(session(s.owner().token()))).andExpect(status().isOk());
		mvc.perform(post("/api/v1/workspaces/" + w + "/accounts/" + s.checking() + "/archive")
				.cookie(session(s.owner().token()))).andExpect(status().isOk());

		assertThat(totalSpent(s.owner())).isEqualTo(26_000);
		assertThat(BudgetScenario.budgetsFingerprint(owner, w)).isEqualTo(limitsBefore);
		week(mvc, s.owner(), "week=2026-W45").andExpect(status().isOk());
		assertThat(base(w)).endsWith("/budgets");
	}

}
