package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.copy;
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

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** História 3: copiar os limites de um mês para os próximos, só onde falta, e atomicamente. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CopyMonthlyBudgetsTest {

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

	private List<LocalDate> months(UUID w) {
		return owner.client().sql("select distinct month_start from monthly_budgets where workspace_id = :w order by 1")
				.param("w", w).query(LocalDate.class).list();
	}

	private long amount(UUID w, LocalDate month, UUID category) {
		return owner.client().sql("""
				select amount_cents from monthly_budgets
				where workspace_id = :w and month_start = :m and category_id is not distinct from :c""")
				.param("w", w).param("m", month).param("c", category).query(Long.class).single();
	}

	@Test
	void copiesOnlyWhatIsMissingAndNeverChangesAnExistingLimit() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		Fixture editor = fixtures().addMember(w, "EDITOR");
		fixtures().insertMonthlyBudget(w, LocalDate.of(2027, 1, 1), s.transport(), 12_345, s.owner().userId());

		copy(mvc, w, editor, "{\"fromMonth\":\"2026-11\",\"months\":3}").andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(11)).andExpect(jsonPath("$.skippedExisting").value(1))
				.andExpect(jsonPath("$.skippedArchivedCategory").value(0));

		// os destinos são exatamente 2026-12, 2027-01 e 2027-02: a virada de ano não pula nem repete mês
		assertThat(months(w)).containsExactly(NOV, LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 1),
				LocalDate.of(2027, 2, 1));
		for (LocalDate month : new LocalDate[] { LocalDate.of(2026, 12, 1), LocalDate.of(2027, 1, 1),
				LocalDate.of(2027, 2, 1) }) {
			assertThat(amount(w, month, s.food())).isEqualTo(64_000);
			assertThat(amount(w, month, s.market())).isEqualTo(20_000);
			assertThat(amount(w, month, null)).isEqualTo(400_000);
		}
		assertThat(amount(w, LocalDate.of(2026, 12, 1), s.transport())).isEqualTo(32_000);
		// o limite que já existia em 2027-01 não mudou
		assertThat(amount(w, LocalDate.of(2027, 1, 1), s.transport())).isEqualTo(12_345);
		// os copiados são de quem copiou (o EDITOR); a origem e o que já existia continuam do dono
		assertThat(owner.client().sql("select created_by from monthly_budgets where workspace_id = :w and month_start = :m and category_id = :c")
				.param("w", w).param("m", LocalDate.of(2026, 12, 1)).param("c", s.food()).query(UUID.class).single())
				.isEqualTo(editor.userId());
		assertThat(owner.client().sql("select created_by from monthly_budgets where workspace_id = :w and month_start = :m and category_id = :c")
				.param("w", w).param("m", NOV).param("c", s.food()).query(UUID.class).single())
				.isEqualTo(s.owner().userId());
		assertThat(fixtures().countMonthlyBudgets(w)).isEqualTo(4 + 11 + 1);
	}

	@Test
	void archivedCategoriesAreSkippedAndCopyingAgainIsIdempotent() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":3}").andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(12));
		owner.client().sql("update categories set archived = true where id = :id").param("id", s.transport()).update();

		// 4 meses de destino (2026-12 a 2027-03): só 2027-03 é novo, e Transporte (arquivada) não é copiado
		copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":4}").andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(3)).andExpect(jsonPath("$.skippedExisting").value(9))
				.andExpect(jsonPath("$.skippedArchivedCategory").value(4));
		// de novo: nada novo
		copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":4}").andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(0)).andExpect(jsonPath("$.skippedExisting").value(12))
				.andExpect(jsonPath("$.skippedArchivedCategory").value(4));
	}

	@Test
	void anOriginWithoutLimitsCopiesNothingWithoutError() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());

		copy(mvc, s.owner().workspaceId(), s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":6}")
				.andExpect(status().isOk()).andExpect(jsonPath("$.created").value(0))
				.andExpect(jsonPath("$.skippedExisting").value(0)).andExpect(jsonPath("$.skippedArchivedCategory").value(0));
		assertThat(fixtures().countMonthlyBudgets(s.owner().workspaceId())).isZero();
	}

	@Test
	void theLimitsOfMonthsAreAcceptedAndBadOnesAreRejectedWithNothingCreated() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();

		copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":1}").andExpect(status().isOk());
		copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":24}").andExpect(status().isOk());
		long before = fixtures().countMonthlyBudgets(w);

		for (String months : new String[] { "0", "25", "-1", "2.5", "null", "true" }) {
			copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":" + months + "}")
					.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("months"));
		}
		copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\"}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("months"));
		for (String bad : new String[] { "2026-1", "1999-12", "2101-01", "\"\"" }) {
			String value = bad.startsWith("\"") ? bad : "\"" + bad + "\"";
			copy(mvc, w, s.owner(), "{\"fromMonth\":" + value + ",\"months\":1}").andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("fromMonth"));
		}
		copy(mvc, w, s.owner(), "{\"months\":1}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("fromMonth"));
		copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":1,\"extra\":1}").andExpect(status().isBadRequest());
		assertThat(fixtures().countMonthlyBudgets(w)).isEqualTo(before);
	}

	@Test
	void destinationsPast2100AreRefusedWholeAndTheLastMonthsAreAccepted() throws Exception {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		fixtures().insertMonthlyBudget(w, LocalDate.of(2100, 10, 1), null, 5_000, s.owner().userId());

		copy(mvc, w, s.owner(), "{\"fromMonth\":\"2100-10\",\"months\":3}").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("months"));
		assertThat(fixtures().countMonthlyBudgets(w)).isEqualTo(1);
		copy(mvc, w, s.owner(), "{\"fromMonth\":\"2100-10\",\"months\":2}").andExpect(status().isOk())
				.andExpect(jsonPath("$.created").value(2));
		copy(mvc, w, s.owner(), "{\"fromMonth\":\"2100-12\",\"months\":1}").andExpect(status().isBadRequest());
		assertThat(months(w)).containsExactly(LocalDate.of(2100, 10, 1), LocalDate.of(2100, 11, 1),
				LocalDate.of(2100, 12, 1));
	}

	@Test
	void aFailureInTheMiddleLeavesNothingWritten() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		long before = fixtures().countMonthlyBudgets(w);
		String monthlyBefore = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w);
		// um gatilho que falha ao gravar o limite geral do 2º mês de destino (2027-01), depois de o 1º (2026-12)
		// já ter sido gravado dentro da mesma instrução
		owner.client().sql("""
				create or replace function test_fail_monthly_copy() returns trigger language plpgsql as $$
				begin
				  if new.month_start = date '2027-01-01' and new.category_id is null then
				    raise exception 'falha de teste na cópia';
				  end if;
				  return new;
				end $$""").update();
		owner.client().sql("""
				create trigger test_fail_monthly_copy before insert on monthly_budgets
				for each row execute function test_fail_monthly_copy()""").update();
		try {
			int code;
			try {
				code = copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":3}").andReturn().getResponse()
						.getStatus();
			} catch (Exception e) {
				code = 500;
			}
			assertThat(code).isGreaterThanOrEqualTo(500);
		} finally {
			owner.client().sql("drop trigger if exists test_fail_monthly_copy on monthly_budgets").update();
			owner.client().sql("drop function if exists test_fail_monthly_copy()").update();
		}

		// nenhum limite da cópia ficou gravado, nem o de 2026-12 que vinha antes da falha
		assertThat(fixtures().countMonthlyBudgets(w)).isEqualTo(before);
		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, w)).isEqualTo(monthlyBefore);
	}

	@Test
	void copyingNeverTouchesWeeklyLimitsOrFinanceData() throws Exception {
		MonthlyBudgetScenario s = seeded();
		UUID w = s.owner().workspaceId();
		fixtures().insertWeeklyBudget(w, LocalDate.of(2026, 11, 2), s.food(), 16_000, s.owner().userId());
		String weeklyBefore = MonthlyBudgetScenario.weeklyBudgetsFingerprint(owner, w);
		String financeBefore = MonthlyBudgetScenario.financeFingerprint(owner, w);

		copy(mvc, w, s.owner(), "{\"fromMonth\":\"2026-11\",\"months\":5}").andExpect(status().isOk());

		assertThat(MonthlyBudgetScenario.weeklyBudgetsFingerprint(owner, w)).isEqualTo(weeklyBefore);
		assertThat(MonthlyBudgetScenario.financeFingerprint(owner, w)).isEqualTo(financeBefore);
	}

}
