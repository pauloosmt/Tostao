package com.tostao.tostao_api.dashboard;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * Cenário do quickstart da 005. Contas {@code Corrente} (saldo inicial 100000) e {@code Poupança} (0);
 * categorias {@code Alimentação} (com a subcategoria {@code Mercado}) e {@code Salário}; lançamentos:
 * <ul>
 * <li>despesa de 20000 em 2026-10-30 (Alimentação, direto) — mês e semana anteriores;</li>
 * <li>despesa de 10000 em 2026-11-02 (Alimentação, direto);</li>
 * <li>despesa de 5000 em 2026-11-03 (Mercado);</li>
 * <li>receita de 250000 em 2026-11-05 (Salário);</li>
 * <li>transferência de 30000 da Corrente para a Poupança em 2026-11-06;</li>
 * <li>despesa de 7777 em 2026-11-04, já excluída.</li>
 * </ul>
 * Em 2026-W45 (02/11 a 08/11): receitas 250000, despesas 15000, resultado 235000.
 */
record DashboardScenario(Fixture owner, UUID checking, UUID savings, UUID food, UUID market, UUID salary) {

	static DashboardScenario create(WorkspaceFixtures fixtures) {
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID checking = fixtures.insertAccount(w, "Corrente", "CHECKING", 100_000, false);
		UUID savings = fixtures.insertAccount(w, "Poupança", "SAVINGS", 0, false);
		UUID food = fixtures.insertCategory(w, null, "Alimentação", "EXPENSE", false);
		UUID market = fixtures.insertCategory(w, food, "Mercado", "EXPENSE", false);
		UUID salary = fixtures.insertCategory(w, null, "Salário", "INCOME", false);

		fixtures.insertLedgerTransaction(w, checking, fx.userId(), food, "EXPENSE", 20_000, LocalDate.of(2026, 10, 30),
				"Mercado do mês", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), food, "EXPENSE", 10_000, LocalDate.of(2026, 11, 2),
				"Restaurante", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), market, "EXPENSE", 5_000, LocalDate.of(2026, 11, 3),
				"Compras", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), salary, "INCOME", 250_000, LocalDate.of(2026, 11, 5),
				"Salário", false);
		fixtures.insertTransferPair(w, checking, savings, fx.userId(), 30_000, LocalDate.of(2026, 11, 6), "Reserva");
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), food, "EXPENSE", 7_777, LocalDate.of(2026, 11, 4),
				"Lançado por engano", true);
		return new DashboardScenario(fx, checking, savings, food, market, salary);
	}

	static String base(UUID workspaceId) {
		return "/api/v1/workspaces/" + workspaceId + "/dashboard";
	}

	/** {@code GET /dashboard?query} como a pessoa dada; {@code query} sem o {@code ?} (pode ser vazio). */
	static ResultActions dashboard(MockMvc mvc, Fixture who, String query) throws Exception {
		return dashboard(mvc, who.workspaceId(), who, query);
	}

	static ResultActions dashboard(MockMvc mvc, UUID workspaceId, Fixture who, String query) throws Exception {
		String url = base(workspaceId) + (query == null || query.isEmpty() ? "" : "?" + query);
		return mvc.perform(get(url).cookie(session(who.token())));
	}

}
