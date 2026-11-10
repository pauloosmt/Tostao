package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
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
 * Cenário da 009. Contas {@code Corrente} (saldo inicial 100000), {@code Poupança} (0) e o cartão {@code Visa}
 * (limite 500000, fecha dia 20 e vence dia 28); categorias {@code Alimentação} (despesa) e {@code Salário}
 * (receita); e uma despesa <b>efetivada</b> de 5000 em 2026-11-02. Os lançamentos pendentes de cada teste são
 * criados por ele, pela API ou pelo banco.
 */
public record PendingScenario(Fixture owner, UUID checking, UUID savings, UUID card, UUID food, UUID salary) {

	public static PendingScenario create(WorkspaceFixtures fixtures) {
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID checking = fixtures.insertAccount(w, "Corrente", "CHECKING", 100_000, false);
		UUID savings = fixtures.insertAccount(w, "Poupança", "SAVINGS", 0, false);
		UUID card = fixtures.insertCard(w, "Visa", 0, 500_000L, 20, 28, false);
		UUID food = fixtures.insertCategory(w, null, "Alimentação", "EXPENSE", false);
		UUID salary = fixtures.insertCategory(w, null, "Salário", "INCOME", false);
		fixtures.insertLedgerTransaction(w, checking, fx.userId(), food, "EXPENSE", 5_000, LocalDate.of(2026, 11, 2),
				"Efetivada", false);
		return new PendingScenario(fx, checking, savings, card, food, salary);
	}

	public static String transactionsUrl(UUID workspaceId) {
		return "/api/v1/workspaces/" + workspaceId + "/transactions";
	}

	/** Corpo de receita ou despesa; {@code status} nulo não aparece no corpo (padrão efetivado). */
	public static String body(String type, UUID account, long cents, String date, String description, UUID category,
			String status) {
		String s = "{\"type\":\"%s\",\"accountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"%s\",\"description\":\"%s\",\"categoryId\":\"%s\""
				.formatted(type, account, cents, date, description, category);
		return s + (status == null ? "" : ",\"status\":\"" + status + "\"") + "}";
	}

	/** Corpo de transferência; {@code status} nulo não aparece no corpo. */
	public static String transferBody(UUID from, UUID to, long cents, String date, String description, String status) {
		String s = "{\"type\":\"TRANSFER\",\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"%s\",\"description\":\"%s\""
				.formatted(from, to, cents, date, description);
		return s + (status == null ? "" : ",\"status\":\"" + status + "\"") + "}";
	}

	public static ResultActions create(MockMvc mvc, Fixture who, String json) throws Exception {
		return mvc.perform(post(transactionsUrl(who.workspaceId())).cookie(session(who.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** {@code PUT /transactions/{id}} com o corpo dado. */
	public static ResultActions edit(MockMvc mvc, Fixture who, UUID id, String json) throws Exception {
		return mvc.perform(put(transactionsUrl(who.workspaceId()) + "/" + id).cookie(session(who.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** {@code POST /transactions/{id}/clear}. */
	public static ResultActions clear(MockMvc mvc, Fixture who, UUID id) throws Exception {
		return mvc.perform(post(transactionsUrl(who.workspaceId()) + "/" + id + "/clear").cookie(session(who.token())));
	}

	public static ResultActions list(MockMvc mvc, Fixture who, String query) throws Exception {
		return mvc.perform(get(transactionsUrl(who.workspaceId()) + (query == null || query.isEmpty() ? "" : "?" + query))
				.cookie(session(who.token())));
	}

	public static ResultActions account(MockMvc mvc, Fixture who, UUID accountId) throws Exception {
		return mvc.perform(get("/api/v1/workspaces/" + who.workspaceId() + "/accounts/" + accountId)
				.cookie(session(who.token())));
	}

	public static ResultActions accounts(MockMvc mvc, Fixture who) throws Exception {
		return mvc.perform(get("/api/v1/workspaces/" + who.workspaceId() + "/accounts").cookie(session(who.token())));
	}

	public static ResultActions balanceReport(MockMvc mvc, Fixture who) throws Exception {
		return mvc.perform(get("/api/v1/workspaces/" + who.workspaceId() + "/reports/balance")
				.cookie(session(who.token())));
	}

	public static ResultActions dashboard(MockMvc mvc, Fixture who, String query) throws Exception {
		return mvc.perform(get("/api/v1/workspaces/" + who.workspaceId() + "/dashboard?" + query)
				.cookie(session(who.token())));
	}

	/** Impressão digital de todas as colunas de {@code transactions} do workspace (ids, estados e datas incluídos). */
	public static String transactionsFingerprint(OwnerJdbc owner, UUID workspaceId) {
		return owner.client().sql("select coalesce(md5(string_agg(x::text, ',' order by x.id)), 'vazio') "
				+ "from transactions x where x.workspace_id = :w").param("w", workspaceId).query(String.class).single();
	}

	/** O estado de um lançamento, lido pelo dono do banco. */
	public static String statusOf(OwnerJdbc owner, UUID transactionId) {
		return owner.client().sql("select status from transactions where id = :id").param("id", transactionId)
				.query(String.class).single();
	}

}
