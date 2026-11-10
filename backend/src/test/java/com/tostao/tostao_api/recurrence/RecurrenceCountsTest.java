package com.tostao.tostao_api.recurrence;

import static com.tostao.tostao_api.recurrence.RecurrenceScenario.createRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.today;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.valid;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.with;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.tostao.tostao_api.automation.AutomationService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * A integração com as specs anteriores (SC-007): as ocorrências pendentes nunca contam no realizado. O workspace A tem
 * recorrências com ocorrências pendentes futuras; o workspace B tem só o que está efetivado, como lançamentos comuns,
 * nas mesmas datas, contas e categorias. Toda consulta de realizado do A tem de devolver o mesmo que a do B, antes e
 * depois da execução automática. Depois, efetivar o que falta faz tudo entrar em exatos centavos.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RecurrenceCountsTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	AutomationService automation;

	/** O banco é compartilhado: deixa de lado os lançamentos marcados e as recorrências que sobraram de outros testes. */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
		owner.client().sql("select auto_clear_due(date '2100-12-31')").query(Integer.class).single();
	}

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	/** Cenário com limites mensais de três meses e semanal da semana de hoje para Aluguel e Mercado. */
	private RecurrenceScenario base() {
		WorkspaceFixtures f = fixtures();
		RecurrenceScenario s = RecurrenceScenario.create(f);
		UUID w = s.owner().workspaceId();
		UUID u = s.owner().userId();
		YearMonth month = YearMonth.from(today());
		for (int i = 0; i < 3; i++) {
			f.insertMonthlyBudget(w, month.plusMonths(i).atDay(1), s.rent(), 1_000_000, u);
			f.insertMonthlyBudget(w, month.plusMonths(i).atDay(1), s.groceries(), 1_000_000, u);
		}
		LocalDate monday = today().with(DayOfWeek.MONDAY);
		f.insertWeeklyBudget(w, monday, s.rent(), 1_000_000, u);
		f.insertWeeklyBudget(w, monday, s.groceries(), 1_000_000, u);
		return s;
	}

	private static String json(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private ResultActions getAs(Fixture who, String path) throws Exception {
		return mvc.perform(get("/api/v1/workspaces/" + who.workspaceId() + path).cookie(session(who.token())));
	}

	private String createIn(RecurrenceScenario s, String body) throws Exception {
		return json(createRecurrence(mvc, s.owner(), body).andExpect(status().isCreated()));
	}

	private record Twins(RecurrenceScenario a, RecurrenceScenario b, String rent, String groceries, String card) {
	}

	/**
	 * A: aluguel (início há 40 dias, sem marcador), mercado (início há 40 dias, com marcador) e uma assinatura no cartão
	 * (início há 5 dias). O aluguel e a assinatura têm a primeira ocorrência efetivada à mão; o mercado ainda não.
	 * B: as ocorrências efetivadas como lançamentos comuns.
	 */
	private Twins twins() throws Exception {
		RecurrenceScenario a = base();
		RecurrenceScenario b = base();
		String rent = createIn(a, with(valid(a), "startOn", "\"" + today().minusDays(40) + "\""));
		String groceries = createIn(a, with(valid(a), "categoryId", "\"" + a.groceries() + "\"", "amountCents", "2000",
				"description", "\"Mercado\"", "startOn", "\"" + today().minusDays(40) + "\"", "autoClear", "true"));
		String card = createIn(a, with(valid(a), "accountId", "\"" + a.card() + "\"", "amountCents", "3000", "description",
				"\"Assinatura\"", "startOn", "\"" + today().minusDays(5) + "\""));
		for (String detail : new String[] { rent, card }) {
			String first = JsonPath.<List<String>>read(detail, "$.occurrences[*].id").get(0);
			clear(mvc, a.owner(), UUID.fromString(first)).andExpect(status().isOk());
		}
		// B: o mesmo efetivado, como lançamentos comuns
		insertCleared(b, b.checking(), b.rent(), 150_000, rent, 1);
		insertCleared(b, b.card(), b.rent(), 3_000, card, 1);
		return new Twins(a, b, rent, groceries, card);
	}

	/** Copia para B as primeiras {@code count} ocorrências (datas) de uma recorrência de A, efetivadas. */
	private void insertCleared(RecurrenceScenario b, UUID account, UUID category, long cents, String detail, int count) {
		List<String> dates = JsonPath.read(detail, "$.occurrences[*].occurredOn");
		for (int i = 0; i < count; i++) {
			fixtures().insertLedgerTransaction(b.owner().workspaceId(), account, b.owner().userId(), category, "EXPENSE", cents,
					LocalDate.parse(dates.get(i)), "Copia", false);
		}
	}

	private static void same(String path, String a, String b) {
		assertThat((Object) JsonPath.read(a, path)).as(path).isEqualTo(JsonPath.read(b, path));
	}

	/** Todas as consultas de realizado das specs anteriores devolvem o mesmo em A e em B. */
	private void assertSameRealized(Twins t) throws Exception {
		String accountsA = json(getAs(t.a().owner(), "/accounts").andExpect(status().isOk()));
		String accountsB = json(getAs(t.b().owner(), "/accounts").andExpect(status().isOk()));
		same("$[*].balanceCents", accountsA, accountsB);

		String reportA = json(getAs(t.a().owner(), "/reports/balance"));
		String reportB = json(getAs(t.b().owner(), "/reports/balance"));
		same("$.totalBalanceCents", reportA, reportB);
		same("$.accounts[*].balanceCents", reportA, reportB);

		YearMonth month = YearMonth.from(today());
		for (int i = 0; i < 3; i++) {
			String period = "month=" + month.plusMonths(i);
			String da = json(getAs(t.a().owner(), "/dashboard?" + period).andExpect(status().isOk()));
			String db = json(getAs(t.b().owner(), "/dashboard?" + period).andExpect(status().isOk()));
			for (String path : new String[] { "$.totals", "$.daily", "$.comparison", "$.balances.totalBalanceCents",
					"$.balances.accounts[*].balanceCents", "$.expenseByCategory[*].totalCents", "$.incomeByCategory[*].totalCents" }) {
				assertThat((Object) JsonPath.read(da, path)).as(period + " " + path).isEqualTo(JsonPath.read(db, path));
			}
			String ba = json(getAs(t.a().owner(), "/monthly-budgets?month=" + month.plusMonths(i)).andExpect(status().isOk()));
			String bb = json(getAs(t.b().owner(), "/monthly-budgets?month=" + month.plusMonths(i)).andExpect(status().isOk()));
			same("$.totalSpentCents", ba, bb);
			same("$.spentOutsideCategoryLimitsCents", ba, bb);
			same("$.budgets[*].spentCents", ba, bb);
			same("$.budgets[*].status", ba, bb);
		}
		String week = today().get(IsoFields.WEEK_BASED_YEAR) + "-W" + "%02d".formatted(today().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
		String wa = json(getAs(t.a().owner(), "/budgets?week=" + week).andExpect(status().isOk()));
		String wb = json(getAs(t.b().owner(), "/budgets?week=" + week).andExpect(status().isOk()));
		same("$.totalSpentCents", wa, wb);
		same("$.budgets[*].spentCents", wa, wb);

		for (int i = 0; i < 3; i++) {
			var ra = getAs(t.a().owner(), "/accounts/" + t.a().card() + "/invoices/" + month.plusMonths(i)).andReturn().getResponse();
			var rb = getAs(t.b().owner(), "/accounts/" + t.b().card() + "/invoices/" + month.plusMonths(i)).andReturn().getResponse();
			assertThat(ra.getStatus()).as("fatura %s", month.plusMonths(i)).isEqualTo(rb.getStatus());
			if (ra.getStatus() == 200) {
				for (String path : new String[] { "$.totalCents", "$.paidCents", "$.remainingCents", "$.status", "$.lines[*].chargeCents" }) {
					same(path, ra.getContentAsString(), rb.getContentAsString());
				}
			}
		}
		String sa = json(getAs(t.a().owner(), "/accounts/" + t.a().card() + "/card-summary"));
		String sb = json(getAs(t.b().owner(), "/accounts/" + t.b().card() + "/card-summary"));
		same("$.owedCents", sa, sb);
		same("$.availableCents", sa, sb);
	}

	@Test
	void pendingOccurrencesNeverMoveAnyRealizedNumber() throws Exception {
		Twins t = twins();

		assertSameRealized(t);

		// os números, para o teste não passar por acaso: só o efetivado mexeu no saldo da Corrente (500000 - 150000)
		String accounts = json(getAs(t.a().owner(), "/accounts"));
		assertThat((List<?>) JsonPath.read(accounts, "$[?(@.name=='Corrente')].balanceCents")).isEqualTo(List.of(350_000));
		// mas o previsto, sim, inclui as pendentes: é bem menor que o saldo
		long pending = ((Number) ((List<?>) JsonPath.read(accounts, "$[?(@.name=='Corrente')].pendingCents")).get(0)).longValue();
		assertThat(pending).isLessThan(-150_000L);
		assertThat((List<?>) JsonPath.read(json(getAs(t.b().owner(), "/accounts")), "$[?(@.name=='Corrente')].pendingCents"))
				.isEqualTo(List.of(0));
	}

	@Test
	void afterTheAutomaticRunTheOccurrencesItClearedCountAndStillMatchTheTwin() throws Exception {
		Twins t = twins();
		int cleared = automation.runOnce(today()).cleared();
		// B recebe as ocorrências de mercado que a rotina efetivou em A (as vencidas), como lançamentos comuns
		List<String> dates = JsonPath.read(json(getAs(t.a().owner(), "/recurrences/" + JsonPath.read(t.groceries(), "$.id"))),
				"$.occurrences[?(@.status=='CLEARED')].occurredOn");
		assertThat(dates).hasSize(cleared).isNotEmpty();
		for (String date : dates) {
			fixtures().insertLedgerTransaction(t.b().owner().workspaceId(), t.b().checking(), t.b().owner().userId(),
					t.b().groceries(), "EXPENSE", 2_000, LocalDate.parse(date), "Copia", false);
		}

		assertSameRealized(t);

		// 500000 - 150000 (aluguel) - 2000 por ocorrência de mercado vencida
		String accounts = json(getAs(t.a().owner(), "/accounts"));
		assertThat((List<?>) JsonPath.read(accounts, "$[?(@.name=='Corrente')].balanceCents"))
				.isEqualTo(List.of(350_000 - 2_000 * dates.size()));
	}

	@Test
	void clearingEveryPendingOccurrenceMakesThemCountInExactCents() throws Exception {
		Twins t = twins();
		UUID w = t.a().owner().workspaceId();
		List<UUID> pending = owner.client().sql("select id from transactions where workspace_id = :w and status = 'PENDING' "
				+ "and deleted_at is null").param("w", w).query(UUID.class).list();
		assertThat(pending).isNotEmpty();

		for (UUID id : pending) {
			clear(mvc, t.a().owner(), id).andExpect(status().isOk());
		}

		// o saldo da Corrente é o inicial mais a soma independente dos efetivados da conta, lida direto do banco
		long expected = 500_000 + owner.client().sql("select coalesce(sum(amount_cents), 0) from transactions where workspace_id = :w "
				+ "and account_id = :a and status = 'CLEARED' and deleted_at is null").param("w", w).param("a", t.a().checking())
				.query(Long.class).single();
		String accounts = json(getAs(t.a().owner(), "/accounts"));
		assertThat(((Number) ((List<?>) JsonPath.read(accounts, "$[?(@.name=='Corrente')].balanceCents")).get(0)).longValue())
				.isEqualTo(expected);
		assertThat((List<?>) JsonPath.read(accounts, "$[?(@.name=='Corrente')].pendingCents")).isEqualTo(List.of(0));
		long cardExpected = owner.client().sql("select coalesce(sum(amount_cents), 0) from transactions where workspace_id = :w "
				+ "and account_id = :a and status = 'CLEARED' and deleted_at is null").param("w", w).param("a", t.a().card())
				.query(Long.class).single();
		assertThat(((Number) ((List<?>) JsonPath.read(accounts, "$[?(@.name=='Visa')].balanceCents")).get(0)).longValue())
				.isEqualTo(cardExpected);
	}

	@Test
	void anIncomeOccurrenceEntersTheBalanceOnlyWhenCleared() throws Exception {
		RecurrenceScenario s = base();
		String salary = createIn(s, with(valid(s), "type", "\"INCOME\"", "categoryId", "\"" + s.salary() + "\"", "amountCents", "500000",
				"description", "\"Salario\"", "startOn", "\"" + today() + "\""));
		String first = JsonPath.<List<String>>read(salary, "$.occurrences[*].id").get(0);

		String before = json(getAs(s.owner(), "/accounts"));
		assertThat((List<?>) JsonPath.read(before, "$[?(@.name=='Corrente')].balanceCents")).isEqualTo(List.of(500_000));

		clear(mvc, s.owner(), UUID.fromString(first)).andExpect(status().isOk());

		String after = json(getAs(s.owner(), "/accounts"));
		assertThat((List<?>) JsonPath.read(after, "$[?(@.name=='Corrente')].balanceCents")).isEqualTo(List.of(1_000_000));
	}

}
