package com.tostao.tostao_api.invoice;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * Cenário do quickstart da 008, com datas de 2024 para a situação das faturas ser sempre a mesma. Cartão
 * {@code Visa} (limite 500000, fecha dia 20 e vence dia 28) com:
 * <ul>
 * <li>despesas de 12345 (20/10, dia do fechamento de outubro), 30000 (21/10), 20000 (05/11), 10000 (20/11, dia do
 * fechamento de novembro) e 5000 (21/11, fatura de dezembro), todas em {@code Mercado};</li>
 * <li>estorno (receita) de 4000 em 12/11 e saque (transferência do cartão para a Corrente) de 6000 em 15/11;</li>
 * <li>despesa de 7777 em 10/11 já excluída;</li>
 * <li>pagamento (transferência da Corrente para o cartão) de 25000 em 25/11.</li>
 * </ul>
 * A fatura de 2024-11 (21/10 a 20/11) soma 62000, com 25000 pagos.
 */
public record InvoiceScenario(Fixture owner, UUID checking, UUID savings, UUID card, UUID food) {

	public static InvoiceScenario create(WorkspaceFixtures fixtures) {
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID checking = fixtures.insertAccount(w, "Corrente", "CHECKING", 0, false);
		UUID savings = fixtures.insertAccount(w, "Poupança", "SAVINGS", 0, false);
		UUID card = fixtures.insertCard(w, "Visa", 0, 500_000L, 20, 28, false);
		UUID food = fixtures.insertCategory(w, null, "Mercado", "EXPENSE", false);

		fixtures.insertLedgerTransaction(w, card, fx.userId(), food, "EXPENSE", 12_345, LocalDate.of(2024, 10, 20),
				"Fechamento de outubro", false);
		fixtures.insertLedgerTransaction(w, card, fx.userId(), food, "EXPENSE", 30_000, LocalDate.of(2024, 10, 21),
				"Primeiro dia", false);
		fixtures.insertLedgerTransaction(w, card, fx.userId(), food, "EXPENSE", 20_000, LocalDate.of(2024, 11, 5),
				"Compra", false);
		fixtures.insertLedgerTransaction(w, card, fx.userId(), null, "INCOME", 4_000, LocalDate.of(2024, 11, 12),
				"Estorno", false);
		fixtures.insertTransferPair(w, card, checking, fx.userId(), 6_000, LocalDate.of(2024, 11, 15), "Saque");
		fixtures.insertLedgerTransaction(w, card, fx.userId(), food, "EXPENSE", 10_000, LocalDate.of(2024, 11, 20),
				"Dia do fechamento", false);
		fixtures.insertLedgerTransaction(w, card, fx.userId(), food, "EXPENSE", 5_000, LocalDate.of(2024, 11, 21),
				"Fatura de dezembro", false);
		fixtures.insertLedgerTransaction(w, card, fx.userId(), food, "EXPENSE", 7_777, LocalDate.of(2024, 11, 10),
				"Lançado por engano", true);
		fixtures.insertTransferPair(w, checking, card, fx.userId(), 25_000, LocalDate.of(2024, 11, 25), "Pagamento");
		return new InvoiceScenario(fx, checking, savings, card, food);
	}

	public static String base(UUID workspaceId, UUID accountId) {
		return "/api/v1/workspaces/" + workspaceId + "/accounts/" + accountId;
	}

	/** {@code GET .../invoices/{month}} como a pessoa dada. */
	public static ResultActions invoice(MockMvc mvc, Fixture who, UUID accountId, String month) throws Exception {
		return mvc.perform(get(base(who.workspaceId(), accountId) + "/invoices/" + month)
				.cookie(session(who.token())));
	}

	/** {@code GET .../invoices?query} ({@code query} sem o {@code ?}, pode ser vazio). */
	public static ResultActions list(MockMvc mvc, Fixture who, UUID accountId, String query) throws Exception {
		return mvc.perform(get(base(who.workspaceId(), accountId) + "/invoices"
				+ (query == null || query.isEmpty() ? "" : "?" + query)).cookie(session(who.token())));
	}

	/** {@code GET .../card-summary}. */
	public static ResultActions summary(MockMvc mvc, Fixture who, UUID accountId) throws Exception {
		return mvc.perform(get(base(who.workspaceId(), accountId) + "/card-summary").cookie(session(who.token())));
	}

	/** Impressão digital dos dados financeiros do workspace: igual antes e depois = a fatura só leu. */
	public static String financeFingerprint(OwnerJdbc owner, UUID workspaceId) {
		StringBuilder all = new StringBuilder();
		for (String table : new String[] { "transactions", "accounts", "categories" }) {
			all.append(owner.client().sql("select coalesce(md5(string_agg(x::text, ',' order by x.id)), 'vazio') from "
					+ table + " x where x.workspace_id = :w").param("w", workspaceId).query(String.class).single());
		}
		return all.toString();
	}

}
