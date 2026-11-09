package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * Cenário do quickstart da 007. Contas {@code Corrente} (saldo inicial 100000) e {@code Poupança} (0);
 * categorias de despesa {@code Alimentação} (com a subcategoria {@code Mercado}), {@code Transporte} e
 * {@code Lazer}, e de receita {@code Salário}. Lançamentos:
 * <ul>
 * <li>despesas de 40000 (01/11, primeiro dia, Alimentação direto), 20000 (15/11, Mercado), 36000 (10/11,
 * Transporte) e 8000 (30/11, último dia, Lazer) — dentro do mês 2026-11;</li>
 * <li>receita de 500000 (05/11) e transferência de 30000 (06/11) — nunca contam como gasto;</li>
 * <li>despesa de 7777 em 04/11 já excluída;</li>
 * <li>despesas de 99999 em 31/10 e de 4000 em 01/12, em Alimentação — fora do mês.</li>
 * </ul>
 * Em 2026-11 o gasto total é 104000.
 */
public record MonthlyBudgetScenario(Fixture owner, UUID checking, UUID savings, UUID food, UUID market,
		UUID transport, UUID leisure, UUID salary) {

	/** Primeiro dia de 2026-11. */
	public static final LocalDate NOV = LocalDate.of(2026, 11, 1);

	public static MonthlyBudgetScenario create(WorkspaceFixtures fixtures) {
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID checking = fixtures.insertAccount(w, "Corrente", "CHECKING", 100_000, false);
		UUID savings = fixtures.insertAccount(w, "Poupança", "SAVINGS", 0, false);
		UUID food = fixtures.insertCategory(w, null, "Alimentação", "EXPENSE", false);
		UUID market = fixtures.insertCategory(w, food, "Mercado", "EXPENSE", false);
		UUID transport = fixtures.insertCategory(w, null, "Transporte", "EXPENSE", false);
		UUID leisure = fixtures.insertCategory(w, null, "Lazer", "EXPENSE", false);
		UUID salary = fixtures.insertCategory(w, null, "Salário", "INCOME", false);

		fixtures.insertLedgerTransaction(w, checking, fx.userId(), food, "EXPENSE", 40_000, LocalDate.of(2026, 11, 1),
				"Primeiro dia", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), market, "EXPENSE", 20_000,
				LocalDate.of(2026, 11, 15), "Compras", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), transport, "EXPENSE", 36_000,
				LocalDate.of(2026, 11, 10), "Combustível", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), leisure, "EXPENSE", 8_000,
				LocalDate.of(2026, 11, 30), "Último dia", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), salary, "INCOME", 500_000,
				LocalDate.of(2026, 11, 5), "Salário", false);
		fixtures.insertTransferPair(w, checking, savings, fx.userId(), 30_000, LocalDate.of(2026, 11, 6), "Reserva");
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), food, "EXPENSE", 7_777, LocalDate.of(2026, 11, 4),
				"Lançado por engano", true);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), food, "EXPENSE", 99_999,
				LocalDate.of(2026, 10, 31), "Mês anterior", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), food, "EXPENSE", 4_000, LocalDate.of(2026, 12, 1),
				"Mês seguinte", false);
		return new MonthlyBudgetScenario(fx, checking, savings, food, market, transport, leisure, salary);
	}

	/** Os quatro limites do quickstart no mês 2026-11, semeados pelo dono do banco. */
	public void seedQuickstartBudgets(WorkspaceFixtures fixtures) {
		UUID w = owner.workspaceId();
		fixtures.insertMonthlyBudget(w, NOV, food, 64_000, owner.userId());
		fixtures.insertMonthlyBudget(w, NOV, market, 20_000, owner.userId());
		fixtures.insertMonthlyBudget(w, NOV, transport, 32_000, owner.userId());
		fixtures.insertMonthlyBudget(w, NOV, null, 400_000, owner.userId());
	}

	public static String base(UUID workspaceId) {
		return "/api/v1/workspaces/" + workspaceId + "/monthly-budgets";
	}

	/** {@code GET /monthly-budgets?query} como a pessoa dada; {@code query} sem o {@code ?} (pode ser vazio). */
	public static ResultActions month(MockMvc mvc, Fixture who, String query) throws Exception {
		return month(mvc, who.workspaceId(), who, query);
	}

	public static ResultActions month(MockMvc mvc, UUID workspaceId, Fixture who, String query) throws Exception {
		String url = base(workspaceId) + (query == null || query.isEmpty() ? "" : "?" + query);
		return mvc.perform(get(url).cookie(session(who.token())));
	}

	/** {@code PUT /monthly-budgets} com o corpo JSON dado. */
	public static ResultActions define(MockMvc mvc, Fixture who, String json) throws Exception {
		return define(mvc, who.workspaceId(), who, json);
	}

	public static ResultActions define(MockMvc mvc, UUID workspaceId, Fixture who, String json) throws Exception {
		return mvc.perform(put(base(workspaceId)).cookie(session(who.token())).contentType(MediaType.APPLICATION_JSON)
				.content(json));
	}

	public static ResultActions update(MockMvc mvc, UUID workspaceId, Fixture who, String id, String json)
			throws Exception {
		return mvc.perform(put(base(workspaceId) + "/" + id).cookie(session(who.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	public static ResultActions remove(MockMvc mvc, UUID workspaceId, Fixture who, String id) throws Exception {
		return mvc.perform(delete(base(workspaceId) + "/" + id).cookie(session(who.token())));
	}

	public static ResultActions copy(MockMvc mvc, UUID workspaceId, Fixture who, String json) throws Exception {
		return mvc.perform(post(base(workspaceId) + "/copy").cookie(session(who.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** Corpo de {@code PUT /monthly-budgets}; {@code categoryId} nulo = limite geral. */
	public static String body(String month, UUID categoryId, long amountCents) {
		return categoryId == null ? "{\"month\":\"%s\",\"amountCents\":%d}".formatted(month, amountCents)
				: "{\"month\":\"%s\",\"categoryId\":\"%s\",\"amountCents\":%d}".formatted(month, categoryId,
						amountCents);
	}

	/** Impressão digital dos dados financeiros (igual à do cenário semanal). */
	public static String financeFingerprint(OwnerJdbc owner, UUID workspaceId) {
		return BudgetScenario.financeFingerprint(owner, workspaceId);
	}

	/** Impressão digital dos limites semanais do workspace. */
	public static String weeklyBudgetsFingerprint(OwnerJdbc owner, UUID workspaceId) {
		return BudgetScenario.budgetsFingerprint(owner, workspaceId);
	}

	/** Impressão digital dos limites mensais do workspace. */
	public static String monthlyBudgetsFingerprint(OwnerJdbc owner, UUID workspaceId) {
		return BudgetScenario.fingerprint(owner, workspaceId, "monthly_budgets");
	}

}
