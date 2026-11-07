package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.BudgetScenario.body;
import static com.tostao.tostao_api.budget.BudgetScenario.define;
import static com.tostao.tostao_api.budget.BudgetScenario.remove;
import static com.tostao.tostao_api.budget.BudgetScenario.update;
import static com.tostao.tostao_api.budget.BudgetScenario.week;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

/** História 4: ajustar o valor de um limite e removê-lo, sem afetar outras semanas. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UpdateDeleteBudgetTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private String json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private long amountInDb(UUID id) {
		return owner.client().sql("select amount_cents from weekly_budgets where id = :id").param("id", id)
				.query(Long.class).single();
	}

	@Test
	void adjustingChangesTheValueAndTheUpdateDateButNotTheSpendingNorTheCreation() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		UUID id = fixtures.insertWeeklyBudget(w, BudgetScenario.W45, s.transport(), 8_000, s.owner().userId());
		String before = json(week(mvc, s.owner(), "week=2026-W45").andExpect(status().isOk()));

		String after = json(update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":9000}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.week").value("2026-W45")).andExpect(jsonPath("$.categoryName").value("Transporte"))
				.andExpect(jsonPath("$.amountCents").value(9_000))
				.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString())));

		assertThat(Instant.parse(JsonPath.read(after, "$.updatedAt")))
				.isAfter(Instant.parse(JsonPath.read(after, "$.createdAt")));
		assertThat(amountInDb(id)).isEqualTo(9_000);
		week(mvc, s.owner(), "week=2026-W45").andExpect(status().isOk())
				.andExpect(jsonPath("$.budgets[0].amountCents").value(9_000))
				.andExpect(jsonPath("$.budgets[0].spentCents").value(9_000))
				.andExpect(jsonPath("$.budgets[0].remainingCents").value(0))
				.andExpect(jsonPath("$.budgets[0].percentUsed").value(100.0))
				.andExpect(jsonPath("$.budgets[0].status").value("WARNING"))
				.andExpect(jsonPath("$.totalSpentCents").value(JsonPath.<Number>read(before, "$.totalSpentCents")
						.longValue()));
	}

	@Test
	void zeroAndTheMaximumAreAcceptedWhenAdjusting() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		UUID id = fixtures.insertWeeklyBudget(w, BudgetScenario.W45, s.transport(), 8_000, s.owner().userId());

		update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":0}").andExpect(status().isOk());
		assertThat(amountInDb(id)).isZero();
		update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":1000000000000}").andExpect(status().isOk());
		assertThat(amountInDb(id)).isEqualTo(1_000_000_000_000L);
	}

	@Test
	void invalidValuesAndExtraFieldsAreRefusedAndNothingChanges() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		UUID id = fixtures.insertWeeklyBudget(w, BudgetScenario.W45, s.transport(), 8_000, s.owner().userId());
		String before = BudgetScenario.budgetsFingerprint(owner, w);

		for (String amount : new String[] { "-1", "1000000000001", "10.5", "true", "null" }) {
			update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":" + amount + "}")
					.andExpect(status().isBadRequest());
		}
		update(mvc, w, s.owner(), id.toString(), "{}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("amountCents"));
		update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":-1}")
				.andExpect(jsonPath("$.errors[0].field").value("amountCents"));
		for (String extra : new String[] { "\"categoryId\":\"" + s.food() + "\"", "\"week\":\"2026-W46\"",
				"\"createdBy\":\"" + UUID.randomUUID() + "\"", "\"workspaceId\":\"" + UUID.randomUUID() + "\"" }) {
			update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":100," + extra + "}")
					.andExpect(status().isBadRequest());
		}

		assertThat(BudgetScenario.budgetsFingerprint(owner, w)).isEqualTo(before);
	}

	@Test
	void adjustingALimitOfAnArchivedCategoryIsRefusedButItKeepsBeingTracked() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		UUID id = fixtures.insertWeeklyBudget(w, BudgetScenario.W45, s.transport(), 8_000, s.owner().userId());
		owner.client().sql("update categories set archived = true where id = :id").param("id", s.transport()).update();

		update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":9000}").andExpect(status().isConflict());

		assertThat(amountInDb(id)).isEqualTo(8_000);
		week(mvc, s.owner(), "week=2026-W45").andExpect(status().isOk())
				.andExpect(jsonPath("$.budgets[0].categoryName").value("Transporte"))
				.andExpect(jsonPath("$.budgets[0].categoryArchived").value(true))
				.andExpect(jsonPath("$.budgets[0].spentCents").value(9_000));
	}

	@Test
	void removingDeletesOnlyThatWeeksLimit() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		UUID id = fixtures.insertWeeklyBudget(w, BudgetScenario.W45, s.food(), 16_000, s.owner().userId());
		fixtures.insertWeeklyBudget(w, BudgetScenario.W45.plusDays(7), s.food(), 16_000, s.owner().userId());
		fixtures.insertWeeklyBudget(w, BudgetScenario.W45, null, 100_000, s.owner().userId());

		remove(mvc, w, s.owner(), id.toString()).andExpect(status().isNoContent());

		assertThat(fixtures.countWeeklyBudgets(w)).isEqualTo(2);
		assertThat(owner.client().sql("select count(*) from weekly_budgets where id = :id").param("id", id)
				.query(Long.class).single()).isZero();
		week(mvc, s.owner(), "week=2026-W45").andExpect(status().isOk())
				.andExpect(jsonPath("$.budgets.length()").value(1))
				.andExpect(jsonPath("$.budgets[0].categoryName").value((Object) null))
				// os 15000 de Alimentação e Mercado e os 9000 de Transporte e 2000 de Lazer saem do alcance dos limites de categoria
				.andExpect(jsonPath("$.spentOutsideCategoryLimitsCents").value(26_000));
		week(mvc, s.owner(), "week=2026-W46").andExpect(status().isOk())
				.andExpect(jsonPath("$.budgets.length()").value(1));
	}

	@Test
	void removingAnArchivedCategorysLimitWorks() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		UUID id = fixtures.insertWeeklyBudget(w, BudgetScenario.W45, s.transport(), 8_000, s.owner().userId());
		owner.client().sql("update categories set archived = true where id = :id").param("id", s.transport()).update();

		remove(mvc, w, s.owner(), id.toString()).andExpect(status().isNoContent());

		assertThat(fixtures.countWeeklyBudgets(w)).isZero();
	}

	@Test
	void unknownRemovedOrForeignOrMalformedIdsAreNotFound() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		BudgetScenario other = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		UUID mine = fixtures.insertWeeklyBudget(w, BudgetScenario.W45, null, 100, s.owner().userId());
		UUID foreign = fixtures.insertWeeklyBudget(other.owner().workspaceId(), BudgetScenario.W45, null, 700,
				other.owner().userId());
		remove(mvc, w, s.owner(), mine.toString()).andExpect(status().isNoContent());

		for (String id : new String[] { mine.toString(), UUID.randomUUID().toString(), foreign.toString(), "abc",
				"123" }) {
			remove(mvc, w, s.owner(), id).andExpect(status().isNotFound());
			update(mvc, w, s.owner(), id, "{\"amountCents\":100}").andExpect(status().isNotFound());
		}
		assertThat(amountInDb(foreign)).isEqualTo(700);
		assertThat(fixtures.countWeeklyBudgets(other.owner().workspaceId())).isEqualTo(1);
	}

	@Test
	void adjustingAndRemovingNeverTouchTransactionsAccountsOrCategories() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		UUID id = fixtures.insertWeeklyBudget(w, BudgetScenario.W45, s.food(), 16_000, s.owner().userId());
		String before = BudgetScenario.financeFingerprint(owner, w);

		update(mvc, w, s.owner(), id.toString(), "{\"amountCents\":1}").andExpect(status().isOk());
		remove(mvc, w, s.owner(), id.toString()).andExpect(status().isNoContent());

		assertThat(BudgetScenario.financeFingerprint(owner, w)).isEqualTo(before);
	}

	@Test
	void anEditorAdjustsAndRemovesLimitsOfOthersWithoutChangingTheCreator() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		Fixture editor = fixtures.addMember(w, "EDITOR");
		UUID id = fixtures.insertWeeklyBudget(w, BudgetScenario.W45, s.food(), 16_000, s.owner().userId());

		update(mvc, w, editor, id.toString(), "{\"amountCents\":17000}").andExpect(status().isOk())
				.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString()));
		remove(mvc, w, editor, id.toString()).andExpect(status().isNoContent());
		// recriar depois de remover é um limite novo, criado por quem recriou
		define(mvc, editor, body("2026-W45", s.food(), 16_000)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.createdBy").value(editor.userId().toString()));
	}

	@Test
	void theTrackingKeepsAddingUpAfterEveryChange() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		fixtures.insertWeeklyBudget(w, BudgetScenario.W45, s.food(), 16_000, s.owner().userId());
		UUID general = fixtures.insertWeeklyBudget(w, BudgetScenario.W45, null, 100_000, s.owner().userId());

		update(mvc, w, s.owner(), general.toString(), "{\"amountCents\":20000}").andExpect(status().isOk());

		String json = json(week(mvc, s.owner(), "week=2026-W45").andExpect(status().isOk()));
		List<Number> spent = JsonPath.read(json, "$.budgets[*].spentCents");
		assertThat(spent).extracting(Number::longValue).containsExactly(26_000L, 15_000L)
				.as("o geral (26000 de 20000) estoura e vem primeiro");
		assertThat(JsonPath.<String>read(json, "$.budgets[0].status")).isEqualTo("EXCEEDED");
	}

}
