package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.BudgetScenario.copy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** História 3: copiar os limites de uma semana para as próximas, só onde falta, de forma atômica. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CopyBudgetsTest {

	private static final LocalDate W45 = BudgetScenario.W45;

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	/** Os limites de uma semana como {@code Nome=valor} (o geral é {@code GERAL}). */
	private List<String> limitsOf(UUID workspaceId, LocalDate monday) {
		return owner.client().sql("""
				select coalesce(c.name, 'GERAL') || '=' || b.amount_cents
				from weekly_budgets b left join categories c on c.id = b.category_id
				where b.workspace_id = :w and b.week_start = :s order by 1""")
				.param("w", workspaceId).param("s", monday).query(String.class).list();
	}

	private static String copyBody(String from, Object weeks) {
		return "{\"fromWeek\":\"" + from + "\",\"weeks\":" + weeks + "}";
	}

	private BudgetScenario scenarioWithLimits(WorkspaceFixtures fixtures) {
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);
		return s;
	}

	@Test
	void copiesOnlyWhatIsMissingAndNeverChangesAnExistingLimit() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = scenarioWithLimits(fixtures);
		UUID w = s.owner().workspaceId();
		fixtures.insertWeeklyBudget(w, W45.plusWeeks(2), s.transport(), 12_345, s.owner().userId());
		List<String> source = limitsOf(w, W45);
		long before = fixtures.countWeeklyBudgets(w);

		copy(mvc, w, s.owner(), copyBody("2026-W45", 3)).andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(11)).andExpect(jsonPath("$.skippedExisting").value(1))
				.andExpect(jsonPath("$.skippedArchivedCategory").value(0));

		assertThat(fixtures.countWeeklyBudgets(w) - before).isEqualTo(11);
		assertThat(limitsOf(w, W45)).isEqualTo(source);
		assertThat(limitsOf(w, W45.plusWeeks(1))).containsExactlyInAnyOrderElementsOf(source);
		assertThat(limitsOf(w, W45.plusWeeks(3))).containsExactlyInAnyOrderElementsOf(source);
		// a semana que já tinha um limite de Transporte diferente o mantém
		assertThat(limitsOf(w, W45.plusWeeks(2))).containsExactlyInAnyOrder("Alimentação=16000", "GERAL=100000",
				"Mercado=5000", "Transporte=12345");
		// nada além das três semanas seguintes
		assertThat(limitsOf(w, W45.plusWeeks(4))).isEmpty();
		assertThat(limitsOf(w, W45.minusWeeks(1))).isEmpty();
	}

	@Test
	void limitsOfArchivedCategoriesAreSkippedAndCountedAndCopyingAgainIsIdempotent() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = scenarioWithLimits(fixtures);
		UUID w = s.owner().workspaceId();
		copy(mvc, w, s.owner(), copyBody("2026-W45", 3)).andExpect(status().isOk());
		owner.client().sql("update categories set archived = true where id = :id").param("id", s.transport()).update();

		copy(mvc, w, s.owner(), copyBody("2026-W45", 4)).andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(3)).andExpect(jsonPath("$.skippedExisting").value(9))
				.andExpect(jsonPath("$.skippedArchivedCategory").value(4));

		assertThat(limitsOf(w, W45.plusWeeks(4))).containsExactlyInAnyOrder("Alimentação=16000", "GERAL=100000",
				"Mercado=5000");
		copy(mvc, w, s.owner(), copyBody("2026-W45", 4)).andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(0)).andExpect(jsonPath("$.skippedExisting").value(12))
				.andExpect(jsonPath("$.skippedArchivedCategory").value(4));
		// o limite de origem de uma categoria arquivada continua intacto na origem
		assertThat(limitsOf(w, W45)).contains("Transporte=8000");
	}

	@Test
	void copiedLimitsBelongToWhoCopiedThemAndTheSourceIsUntouched() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = scenarioWithLimits(fixtures);
		UUID w = s.owner().workspaceId();
		Fixture editor = fixtures.addMember(w, "EDITOR");
		String sourceBefore = owner.client().sql("""
				select md5(string_agg(x::text, ',' order by x.id)) from weekly_budgets x
				where x.workspace_id = :w and x.week_start = :s""").param("w", w).param("s", W45).query(String.class)
				.single();

		copy(mvc, w, editor, copyBody("2026-W45", 2)).andExpect(status().isOk()).andExpect(jsonPath("$.created").value(8));

		assertThat(owner.client().sql("""
				select count(*) from weekly_budgets where workspace_id = :w and week_start > :s and created_by = :u""")
				.param("w", w).param("s", W45).param("u", editor.userId()).query(Long.class).single()).isEqualTo(8);
		assertThat(owner.client().sql("""
				select md5(string_agg(x::text, ',' order by x.id)) from weekly_budgets x
				where x.workspace_id = :w and x.week_start = :s""").param("w", w).param("s", W45).query(String.class)
				.single()).isEqualTo(sourceBefore);
	}

	@Test
	void aSourceWithoutLimitsCreatesNothingAndIsNotAnError() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		copy(mvc, s.owner().workspaceId(), s.owner(), copyBody("2026-W45", 5)).andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(0)).andExpect(jsonPath("$.skippedExisting").value(0))
				.andExpect(jsonPath("$.skippedArchivedCategory").value(0));
		assertThat(fixtures().countWeeklyBudgets(s.owner().workspaceId())).isZero();
	}

	@Test
	void bothEndsOfTheAllowedCountWork() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = scenarioWithLimits(fixtures);
		UUID w = s.owner().workspaceId();

		copy(mvc, w, s.owner(), copyBody("2026-W45", 1)).andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(4));
		copy(mvc, w, s.owner(), copyBody("2026-W45", 52)).andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(4 * 51)).andExpect(jsonPath("$.skippedExisting").value(4));
		assertThat(fixtures.countWeeklyBudgets(w)).isEqualTo(4 * 53);
	}

	@Test
	void invalidCountsAndWeeksAreRefusedNamingTheFieldAndNothingIsCreated() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = scenarioWithLimits(fixtures);
		UUID w = s.owner().workspaceId();
		String before = BudgetScenario.budgetsFingerprint(owner, w);

		for (String weeks : new String[] { "0", "53", "-1", "2.5", "null", "true" }) {
			copy(mvc, w, s.owner(), copyBody("2026-W45", weeks)).andExpect(status().isBadRequest());
		}
		copy(mvc, w, s.owner(), "{\"fromWeek\":\"2026-W45\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("weeks"));
		copy(mvc, w, s.owner(), copyBody("2026-W45", 0)).andExpect(jsonPath("$.errors[0].field").value("weeks"));
		for (String from : new String[] { "2026-W5", "2025-W53", "1999-W52", "2101-W01", "2026W45", "" }) {
			copy(mvc, w, s.owner(), copyBody(from, 3)).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("fromWeek"));
		}
		copy(mvc, w, s.owner(), "{\"weeks\":3}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("fromWeek"));
		copy(mvc, w, s.owner(), "{\"fromWeek\":\"2026-W45\",\"weeks\":3,\"workspaceId\":\"" + UUID.randomUUID() + "\"}")
				.andExpect(status().isBadRequest());

		assertThat(BudgetScenario.budgetsFingerprint(owner, w)).isEqualTo(before);
	}

	@Test
	void destinationsPast2100AreRefusedWhole() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		// 2100-W50 começa em 2100-12-13; 2100-W52 é a última semana aceita
		fixtures.insertWeeklyBudget(w, LocalDate.of(2100, 12, 13), null, 1_000, s.owner().userId());
		long before = fixtures.countWeeklyBudgets(w);

		copy(mvc, w, s.owner(), copyBody("2100-W50", 3)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("weeks"));
		copy(mvc, w, s.owner(), copyBody("2100-W50", 5)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("weeks"));
		assertThat(fixtures.countWeeklyBudgets(w)).isEqualTo(before);

		copy(mvc, w, s.owner(), copyBody("2100-W50", 2)).andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(2));
		assertThat(limitsOf(w, LocalDate.of(2100, 12, 27))).containsExactly("GERAL=1000");

		copy(mvc, w, s.owner(), copyBody("2100-W52", 1)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("weeks"));
	}

	@Test
	void aFailureInTheMiddleLeavesNoLimitSaved() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = scenarioWithLimits(fixtures);
		UUID w = s.owner().workspaceId();
		long before = fixtures.countWeeklyBudgets(w);
		// o gatilho de teste levanta erro ao gravar a quarta semana de destino (2026-11-30), depois de as
		// três primeiras já terem sido gravadas pela mesma instrução
		owner.client().sql("""
				create function budget_test_fail() returns trigger language plpgsql as $$
				begin
				    if new.week_start = date '2026-11-30' then raise exception 'falha de teste'; end if;
				    return new;
				end $$""").update();
		owner.client().sql("""
				create trigger budget_test_fail before insert on weekly_budgets
				for each row execute function budget_test_fail()""").update();
		try {
			assertThatThrownBy(() -> copy(mvc, w, s.owner(), copyBody("2026-W45", 4)));

			assertThat(fixtures.countWeeklyBudgets(w)).isEqualTo(before);
			assertThat(limitsOf(w, W45.plusWeeks(1))).isEmpty();
			assertThat(limitsOf(w, W45.plusWeeks(3))).isEmpty();
		} finally {
			owner.client().sql("drop trigger budget_test_fail on weekly_budgets").update();
			owner.client().sql("drop function budget_test_fail()").update();
		}
		// sem o gatilho, a mesma cópia funciona
		copy(mvc, w, s.owner(), copyBody("2026-W45", 4)).andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(16));
	}

}
