package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.body;
import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.copy;
import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.define;
import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.month;
import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.remove;
import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * Roteiro do quickstart da 007 (passos 3 a 8) de ponta a ponta, com os números do cenário: definir,
 * acompanhar, ajustar, remover e copiar limites mensais, mais as recusas e a independência do semanal.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MonthlyBudgetQuickstartTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private static String idOf(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private List<LocalDate> monthsWithLimits(UUID workspaceId) {
		return owner.client().sql("select distinct month_start from monthly_budgets where workspace_id = :w order by 1")
				.param("w", workspaceId).query(LocalDate.class).list();
	}

	@Test
	void theQuickstartFlowWorksEndToEnd() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		Fixture me = s.owner();
		UUID w = me.workspaceId();

		// 3. definir: 201 com Location; o mesmo corpo de novo é 200 com o mesmo id
		ResultActions first = define(mvc, me, body("2026-11", s.food(), 64_000)).andExpect(status().isCreated())
				.andExpect(header().string("Location", containsString("/api/v1/workspaces/" + w + "/monthly-budgets/")))
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(jsonPath("$.month").value("2026-11"))
				.andExpect(jsonPath("$.categoryName").value("Alimentação"))
				.andExpect(jsonPath("$.amountCents").value(64_000))
				.andExpect(jsonPath("$.createdBy").value(me.userId().toString()));
		String foodId = idOf(first);
		define(mvc, me, body("2026-11", s.food(), 64_000)).andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(foodId));
		define(mvc, me, body("2026-11", s.market(), 20_000)).andExpect(status().isCreated());
		String transportId = idOf(define(mvc, me, body("2026-11", s.transport(), 32_000)).andExpect(status().isCreated()));
		define(mvc, me, body("2026-11", null, 400_000)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.categoryId").doesNotExist());
		assertThat(fixtures().countMonthlyBudgets(w)).isEqualTo(4);
		assertThat(owner.client().sql("select month_start from monthly_budgets where id = :id")
				.param("id", UUID.fromString(foodId)).query(LocalDate.class).single()).isEqualTo(LocalDate.of(2026, 11, 1));

		// 4. acompanhar: 104000 gastos, 8000 fora dos limites (Lazer), e a ordem dos quatro limites
		month(mvc, me, "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.month.id").value("2026-11"))
				.andExpect(jsonPath("$.month.startDate").value("2026-11-01"))
				.andExpect(jsonPath("$.month.endDate").value("2026-11-30"))
				.andExpect(jsonPath("$.totalSpentCents").value(104_000))
				.andExpect(jsonPath("$.spentOutsideCategoryLimitsCents").value(8_000))
				.andExpect(jsonPath("$.budgets.length()").value(4))
				.andExpect(jsonPath("$.budgets[0].categoryName").value("Transporte"))
				.andExpect(jsonPath("$.budgets[0].spentCents").value(36_000))
				.andExpect(jsonPath("$.budgets[0].remainingCents").value(-4_000))
				.andExpect(jsonPath("$.budgets[0].percentUsed").value(112.5))
				.andExpect(jsonPath("$.budgets[0].status").value("EXCEEDED"))
				.andExpect(jsonPath("$.budgets[1].categoryName").value("Alimentação"))
				.andExpect(jsonPath("$.budgets[1].spentCents").value(60_000))
				.andExpect(jsonPath("$.budgets[1].remainingCents").value(4_000))
				.andExpect(jsonPath("$.budgets[1].percentUsed").value(93.75))
				.andExpect(jsonPath("$.budgets[1].status").value("WARNING"))
				.andExpect(jsonPath("$.budgets[2].categoryName").value("Mercado"))
				.andExpect(jsonPath("$.budgets[2].spentCents").value(20_000))
				.andExpect(jsonPath("$.budgets[2].remainingCents").value(0))
				.andExpect(jsonPath("$.budgets[2].percentUsed").value(100.0))
				.andExpect(jsonPath("$.budgets[2].status").value("WARNING"))
				.andExpect(jsonPath("$.budgets[3].categoryId").doesNotExist())
				.andExpect(jsonPath("$.budgets[3].spentCents").value(84_000))
				.andExpect(jsonPath("$.budgets[3].remainingCents").value(316_000))
				.andExpect(jsonPath("$.budgets[3].percentUsed").value(21.0))
				.andExpect(jsonPath("$.budgets[3].status").value("WITHIN"));

		// primeiro e último dia contam; 31/10 e 01/12 não (o total já provou: 40000 de 01/11 e 8000 de 30/11)
		// limite zero em Lazer: estourado, sem percentual, e o fora dos limites cai para 0
		String leisureId = idOf(define(mvc, me, body("2026-11", s.leisure(), 0)).andExpect(status().isCreated()));
		month(mvc, me, "month=2026-11").andExpect(jsonPath("$.spentOutsideCategoryLimitsCents").value(0))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Lazer')].status").value("EXCEEDED"))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Lazer')].percentUsed").value((Object) null));

		// um mês sem limites: lista vazia e o total do mês
		month(mvc, me, "month=2026-10").andExpect(status().isOk()).andExpect(jsonPath("$.budgets.length()").value(0))
				.andExpect(jsonPath("$.totalSpentCents").value(99_999));

		// 5. ajustar e remover
		update(mvc, w, me, transportId, "{\"amountCents\":36000}").andExpect(status().isOk())
				.andExpect(jsonPath("$.amountCents").value(36_000));
		month(mvc, me, "month=2026-11").andExpect(jsonPath("$.budgets[?(@.categoryName=='Transporte')].status")
				.value("WARNING"))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Transporte')].percentUsed").value(100.0));
		remove(mvc, w, me, leisureId).andExpect(status().isNoContent());
		remove(mvc, w, me, leisureId).andExpect(status().isNotFound());

		// 6. copiar: Transporte 12345 já em 2027-01; destinos 2026-12, 2027-01 e 2027-02 (virada de ano)
		define(mvc, me, body("2027-01", s.transport(), 12_345)).andExpect(status().isCreated());
		copy(mvc, w, me, "{\"fromMonth\":\"2026-11\",\"months\":3}").andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(11)).andExpect(jsonPath("$.skippedExisting").value(1))
				.andExpect(jsonPath("$.skippedArchivedCategory").value(0));
		assertThat(monthsWithLimits(w)).containsExactly(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1),
				LocalDate.of(2027, 1, 1), LocalDate.of(2027, 2, 1));
		month(mvc, me, "month=2027-01").andExpect(jsonPath("$.budgets[?(@.categoryName=='Transporte')].amountCents")
				.value(12_345));

		// arquivar Transporte e copiar 4 meses: só 2027-03 é novo
		owner.client().sql("update categories set archived = true where id = :id").param("id", s.transport()).update();
		copy(mvc, w, me, "{\"fromMonth\":\"2026-11\",\"months\":4}").andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(3)).andExpect(jsonPath("$.skippedExisting").value(9))
				.andExpect(jsonPath("$.skippedArchivedCategory").value(4));
		// o limite da categoria arquivada segue aparecendo; ajustar é 409 e remover é 204
		month(mvc, me, "month=2026-11").andExpect(jsonPath("$.budgets[?(@.categoryName=='Transporte')].categoryArchived")
				.value(true));
		update(mvc, w, me, transportId, "{\"amountCents\":1}").andExpect(status().isConflict());
		remove(mvc, w, me, transportId).andExpect(status().isNoContent());
	}

	@Test
	void invalidInputIsRejectedNamingTheFieldAndNothingIsWritten() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		Fixture me = s.owner();
		UUID w = me.workspaceId();

		for (String bad : new String[] { "2026-1", "202611", "2026-13", "2026-00", "1999-12", "2101-01", "2026-11-01" }) {
			define(mvc, me, body(bad, s.food(), 100)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("month"));
			month(mvc, me, "month=" + bad).andExpect(status().isBadRequest());
		}
		define(mvc, me, body("2026-11", s.food(), -1)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("amountCents"));
		define(mvc, me, body("2026-11", s.salary(), 100)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("categoryId"));
		define(mvc, me, "{\"month\":\"2026-11\",\"amountCents\":100,\"createdBy\":\"" + UUID.randomUUID() + "\"}")
				.andExpect(status().isBadRequest());
		copy(mvc, w, me, "{\"fromMonth\":\"2026-11\",\"months\":25}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("months"));
		copy(mvc, w, me, "{\"fromMonth\":\"2026-1\",\"months\":1}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("fromMonth"));
		copy(mvc, w, me, "{\"fromMonth\":\"2100-10\",\"months\":3}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("months"));

		assertThat(fixtures().countMonthlyBudgets(w)).isZero();
	}

	@Test
	void anOutsiderGetsNotFoundAndAViewerCannotWrite() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		Fixture me = s.owner();
		UUID w = me.workspaceId();
		Fixture outsider = fixtures().workspaceWithOwner();
		Fixture viewer = fixtures().addMember(w, "VIEWER");
		String id = idOf(define(mvc, me, body("2026-11", s.food(), 64_000)).andExpect(status().isCreated()));

		month(mvc, w, outsider, "month=2026-11").andExpect(status().isNotFound());
		define(mvc, w, outsider, body("2026-11", s.food(), 1)).andExpect(status().isNotFound());
		update(mvc, w, outsider, id, "{\"amountCents\":1}").andExpect(status().isNotFound());
		remove(mvc, w, outsider, id).andExpect(status().isNotFound());
		copy(mvc, w, outsider, "{\"fromMonth\":\"2026-11\",\"months\":1}").andExpect(status().isNotFound());

		month(mvc, w, viewer, "month=2026-11").andExpect(status().isOk());
		define(mvc, w, viewer, body("2026-11", s.food(), 1)).andExpect(status().isForbidden());
		update(mvc, w, viewer, id, "{\"amountCents\":1}").andExpect(status().isForbidden());
		remove(mvc, w, viewer, id).andExpect(status().isForbidden());
		copy(mvc, w, viewer, "{\"fromMonth\":\"2026-11\",\"months\":1}").andExpect(status().isForbidden());

		assertThat(owner.client().sql("select amount_cents from monthly_budgets where id = :id")
				.param("id", UUID.fromString(id)).query(Long.class).single()).isEqualTo(64_000);
	}

	@Test
	void monthlyAndWeeklyLimitsNeverTouchEachOther() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		Fixture me = s.owner();
		UUID w = me.workspaceId();
		UUID weekly = fixtures().insertWeeklyBudget(w, LocalDate.of(2026, 11, 2), s.food(), 16_000, me.userId());
		String monthlyId = idOf(define(mvc, me, body("2026-11", s.food(), 64_000)).andExpect(status().isCreated()));
		String weeklyBefore = MonthlyBudgetScenario.weeklyBudgetsFingerprint(owner, w);

		// um id semanal numa rota mensal não é encontrado, e o contrário também
		update(mvc, w, me, weekly.toString(), "{\"amountCents\":1}").andExpect(status().isNotFound());
		remove(mvc, w, me, weekly.toString()).andExpect(status().isNotFound());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/api/v1/workspaces/" + w + "/budgets/" + monthlyId)
				.cookie(com.tostao.tostao_api.support.AuthSupport.session(me.token()))).andExpect(status().isNotFound());

		// operações mensais não mexem nos limites semanais
		copy(mvc, w, me, "{\"fromMonth\":\"2026-11\",\"months\":2}").andExpect(status().isOk());
		remove(mvc, w, me, monthlyId).andExpect(status().isNoContent());
		assertThat(MonthlyBudgetScenario.weeklyBudgetsFingerprint(owner, w)).isEqualTo(weeklyBefore);
		assertThat(fixtures().countWeeklyBudgets(w)).isEqualTo(1);
	}

}
