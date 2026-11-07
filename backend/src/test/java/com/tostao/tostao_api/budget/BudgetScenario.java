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
 * Cenário do quickstart da 006. Contas {@code Corrente} (saldo inicial 100000) e {@code Poupança} (0);
 * categorias de despesa {@code Alimentação} (com a subcategoria {@code Mercado}), {@code Transporte} e
 * {@code Lazer}, e de receita {@code Salário}. Lançamentos:
 * <ul>
 * <li>despesas de 10000 (02/11, Alimentação direto), 5000 (03/11, Mercado), 9000 (04/11, Transporte) e 2000
 * (05/11, Lazer) — dentro da semana 2026-W45 (02/11 a 08/11);</li>
 * <li>receita de 250000 (05/11) e transferência de 30000 (06/11) — nunca contam como gasto;</li>
 * <li>despesa de 7777 em 04/11 já excluída;</li>
 * <li>despesas de 99999 em 01/11 (domingo anterior) e de 4000 em 09/11 (segunda seguinte), em Alimentação —
 * fora da semana.</li>
 * </ul>
 * Em 2026-W45 o gasto total é 26000.
 */
public record BudgetScenario(Fixture owner, UUID checking, UUID savings, UUID food, UUID market, UUID transport,
		UUID leisure, UUID salary) {

	/** Segunda-feira de 2026-W45. */
	public static final LocalDate W45 = LocalDate.of(2026, 11, 2);

	public static BudgetScenario create(WorkspaceFixtures fixtures) {
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID checking = fixtures.insertAccount(w, "Corrente", "CHECKING", 100_000, false);
		UUID savings = fixtures.insertAccount(w, "Poupança", "SAVINGS", 0, false);
		UUID food = fixtures.insertCategory(w, null, "Alimentação", "EXPENSE", false);
		UUID market = fixtures.insertCategory(w, food, "Mercado", "EXPENSE", false);
		UUID transport = fixtures.insertCategory(w, null, "Transporte", "EXPENSE", false);
		UUID leisure = fixtures.insertCategory(w, null, "Lazer", "EXPENSE", false);
		UUID salary = fixtures.insertCategory(w, null, "Salário", "INCOME", false);

		fixtures.insertLedgerTransaction(w, checking, fx.userId(), food, "EXPENSE", 10_000, LocalDate.of(2026, 11, 2),
				"Restaurante", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), market, "EXPENSE", 5_000, LocalDate.of(2026, 11, 3),
				"Compras", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), transport, "EXPENSE", 9_000,
				LocalDate.of(2026, 11, 4), "Combustível", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), leisure, "EXPENSE", 2_000, LocalDate.of(2026, 11, 5),
				"Cinema", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), salary, "INCOME", 250_000, LocalDate.of(2026, 11, 5),
				"Salário", false);
		fixtures.insertTransferPair(w, checking, savings, fx.userId(), 30_000, LocalDate.of(2026, 11, 6), "Reserva");
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), food, "EXPENSE", 7_777, LocalDate.of(2026, 11, 4),
				"Lançado por engano", true);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), food, "EXPENSE", 99_999, LocalDate.of(2026, 11, 1),
				"Domingo anterior", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), food, "EXPENSE", 4_000, LocalDate.of(2026, 11, 9),
				"Segunda seguinte", false);
		return new BudgetScenario(fx, checking, savings, food, market, transport, leisure, salary);
	}

	/** Os quatro limites do quickstart na semana 2026-W45, semeados pelo dono do banco. */
	public void seedQuickstartBudgets(WorkspaceFixtures fixtures) {
		UUID w = owner.workspaceId();
		fixtures.insertWeeklyBudget(w, W45, food, 16_000, owner.userId());
		fixtures.insertWeeklyBudget(w, W45, market, 5_000, owner.userId());
		fixtures.insertWeeklyBudget(w, W45, transport, 8_000, owner.userId());
		fixtures.insertWeeklyBudget(w, W45, null, 100_000, owner.userId());
	}

	public static String base(UUID workspaceId) {
		return "/api/v1/workspaces/" + workspaceId + "/budgets";
	}

	/** {@code GET /budgets?query} como a pessoa dada; {@code query} sem o {@code ?} (pode ser vazio). */
	public static ResultActions week(MockMvc mvc, Fixture who, String query) throws Exception {
		return week(mvc, who.workspaceId(), who, query);
	}

	public static ResultActions week(MockMvc mvc, UUID workspaceId, Fixture who, String query) throws Exception {
		String url = base(workspaceId) + (query == null || query.isEmpty() ? "" : "?" + query);
		return mvc.perform(get(url).cookie(session(who.token())));
	}

	/** {@code PUT /budgets} com o corpo JSON dado. */
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

	/** Corpo de {@code PUT /budgets}; {@code categoryId} nulo = limite geral. */
	public static String body(String week, UUID categoryId, long amountCents) {
		return categoryId == null ? "{\"week\":\"%s\",\"amountCents\":%d}".formatted(week, amountCents)
				: "{\"week\":\"%s\",\"categoryId\":\"%s\",\"amountCents\":%d}".formatted(week, categoryId, amountCents);
	}

	/**
	 * Impressão digital dos dados financeiros do workspace (todas as colunas de {@code transactions},
	 * {@code accounts} e {@code categories}). Igual antes e depois = o orçamento não tocou neles.
	 */
	public static String financeFingerprint(OwnerJdbc owner, UUID workspaceId) {
		return fingerprint(owner, workspaceId, "transactions", "accounts", "categories");
	}

	/** Impressão digital dos limites semanais do workspace. */
	public static String budgetsFingerprint(OwnerJdbc owner, UUID workspaceId) {
		return fingerprint(owner, workspaceId, "weekly_budgets");
	}

	private static String fingerprint(OwnerJdbc owner, UUID workspaceId, String... tables) {
		StringBuilder all = new StringBuilder();
		for (String table : tables) {
			all.append(owner.client().sql("select coalesce(md5(string_agg(x::text, ',' order by x.id)), 'vazio') from "
					+ table + " x where x.workspace_id = :w").param("w", workspaceId).query(String.class).single());
		}
		return all.toString();
	}

}
