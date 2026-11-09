package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.month;
import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.remove;
import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
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

/** História 4: ajustar o valor de um limite mensal ou removê-lo, sem afetar outros meses. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UpdateDeleteMonthlyBudgetTest {

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

	private MonthlyBudgetScenario seeded() {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		s.seedQuickstartBudgets(fixtures());
		return s;
	}

	private UUID limitId(UUID workspaceId, UUID category, LocalDate monthStart) {
		return owner.client().sql("""
				select id from monthly_budgets
				where workspace_id = :w and month_start = :m and category_id = :c""")
				.param("w", workspaceId).param("m", monthStart).param("c", category).query(UUID.class).single();
	}

	@Test
	void changesTheValueKeepingTheCreatorAndTheCreationDate() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		UUID id = limitId(w, s.transport(), NOV);
		Instant createdBefore = owner.client().sql("select created_at from monthly_budgets where id = :id")
				.param("id", id).query(java.time.OffsetDateTime.class).single().toInstant();

		String json = update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":36000}").andExpect(status().isOk())
				.andExpect(jsonPath("$.amountCents").value(36_000)).andExpect(jsonPath("$.month").value("2026-11"))
				.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString())).andReturn().getResponse()
				.getContentAsString();

		assertThat(Instant.parse(JsonPath.read(json, "$.createdAt"))).isEqualTo(createdBefore);
		assertThat(Instant.parse(JsonPath.read(json, "$.updatedAt"))).isAfterOrEqualTo(createdBefore);
		// o acompanhamento passa a usar o valor novo (gasto 36000 de 36000: atenção a 100%) e o gasto não muda
		month(mvc, s.owner(), "month=2026-11")
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Transporte')].amountCents").value(36_000))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Transporte')].spentCents").value(36_000))
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Transporte')].status").value("WARNING"))
				.andExpect(jsonPath("$.totalSpentCents").value(104_000));
	}

	@Test
	void zeroAndTheMaximumAreAcceptedAndBadAmountsAreRejected() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		UUID id = limitId(w, s.food(), NOV);

		update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":0}").andExpect(status().isOk());
		update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":1000000000000}").andExpect(status().isOk());
		for (String bad : new String[] { "-1", "1000000000001", "10.5", "null", "true" }) {
			update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":" + bad + "}").andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("amountCents"));
		}
		update(mvc, w, s.owner(), id.toString(), "{}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("amountCents"));
		assertThat(owner.client().sql("select amount_cents from monthly_budgets where id = :id").param("id", id)
				.query(Long.class).single()).isEqualTo(1_000_000_000_000L);
	}

	@Test
	void extraFieldsAreRejectedAndNothingChanges() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		UUID id = limitId(w, s.food(), NOV);
		String before = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w);

		for (String extra : new String[] { "\"categoryId\":\"" + s.leisure() + "\"", "\"month\":\"2026-12\"",
				"\"createdBy\":\"" + UUID.randomUUID() + "\"" }) {
			update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":1," + extra + "}")
					.andExpect(status().isBadRequest());
		}
		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w)).isEqualTo(before);
	}

	@Test
	void aLimitOfAnArchivedCategoryCannotChangeButStaysVisibleAndCanBeRemoved() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		UUID id = limitId(w, s.transport(), NOV);
		owner.client().sql("update categories set archived = true where id = :id").param("id", s.transport()).update();

		update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":1}").andExpect(status().isConflict());
		assertThat(owner.client().sql("select amount_cents from monthly_budgets where id = :id").param("id", id)
				.query(Long.class).single()).isEqualTo(32_000);
		month(mvc, s.owner(), "month=2026-11")
				.andExpect(jsonPath("$.budgets[?(@.categoryName=='Transporte')].categoryArchived").value(true));
		remove(mvc, w, s.owner(), id.toString()).andExpect(status().isNoContent());
	}

	@Test
	void removingDeletesOnlyThatMonthAndTheCoveredSpendingBecomesOutsideTheLimits() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		fixtures().insertMonthlyBudget(w, LocalDate.of(2026, 12, 1), s.transport(), 7_000, s.owner().userId());
		UUID id = limitId(w, s.transport(), NOV);
		String financeBefore = MonthlyBudgetScenario.financeFingerprint(owner, w);
		String weeklyBefore = MonthlyBudgetScenario.weeklyBudgetsFingerprint(owner, w);

		remove(mvc, w, s.owner(), id.toString()).andExpect(status().isNoContent());

		assertThat(fixtures().countMonthlyBudgets(w)).isEqualTo(4 + 1 - 1);
		month(mvc, s.owner(), "month=2026-11").andExpect(jsonPath("$.budgets.length()").value(3))
				// o gasto de Transporte (36000) soma ao de Lazer (8000) agora que nenhum limite de categoria o cobre
				.andExpect(jsonPath("$.spentOutsideCategoryLimitsCents").value(44_000));
		month(mvc, s.owner(), "month=2026-12").andExpect(jsonPath("$.budgets.length()").value(1));
		assertThat(MonthlyBudgetScenario.financeFingerprint(owner, w)).isEqualTo(financeBefore);
		assertThat(MonthlyBudgetScenario.weeklyBudgetsFingerprint(owner, w)).isEqualTo(weeklyBefore);
	}

	@Test
	void anUnknownRemovedOtherWorkspaceOrMalformedIdIsNotFound() throws Exception {
		MonthlyBudgetScenario s = seeded();
		MonthlyBudgetScenario other = seeded();
		UUID w = s.owner().workspaceId();
		UUID foreign = limitId(other.owner().workspaceId(), other.food(), NOV);
		UUID gone = limitId(w, s.market(), NOV);
		remove(mvc, w, s.owner(), gone.toString()).andExpect(status().isNoContent());

		for (String id : new String[] { gone.toString(), UUID.randomUUID().toString(), foreign.toString(), "abc" }) {
			update(mvc, w, s.owner(), id, "{\"amountCents\":1}").andExpect(status().isNotFound());
			remove(mvc, w, s.owner(), id).andExpect(status().isNotFound());
		}
		assertThat(owner.client().sql("select amount_cents from monthly_budgets where id = :id").param("id", foreign)
				.query(Long.class).single()).isEqualTo(64_000);
	}

	@Test
	void anEditorChangesAndRemovesAnotherPersonsLimitAndAnAnotherWeeklyIdIsNotFound() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		Fixture editor = fixtures().addMember(w, "EDITOR");
		UUID id = limitId(w, s.food(), NOV);
		UUID weekly = fixtures().insertWeeklyBudget(w, LocalDate.of(2026, 11, 2), s.food(), 16_000, s.owner().userId());

		update(mvc, w, editor, id.toString(), "{\"amountCents\":5}").andExpect(status().isOk())
				.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString()));
		// um id semanal não existe nas rotas mensais, e o limite semanal fica intacto
		update(mvc, w, editor, weekly.toString(), "{\"amountCents\":5}").andExpect(status().isNotFound());
		remove(mvc, w, editor, weekly.toString()).andExpect(status().isNotFound());
		assertThat(fixtures().countWeeklyBudgets(w)).isEqualTo(1);
		remove(mvc, w, editor, id.toString()).andExpect(status().isNoContent());
	}

	@Test
	void afterRemovingTheLimitCanBeRecreatedByWhoDefinesItAgain() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		Fixture editor = fixtures().addMember(w, "EDITOR");
		remove(mvc, w, s.owner(), limitId(w, s.food(), NOV).toString()).andExpect(status().isNoContent());

		MonthlyBudgetScenario.define(mvc, editor, MonthlyBudgetScenario.body("2026-11", s.food(), 1_000))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.createdBy").value(editor.userId().toString()));
	}

}
