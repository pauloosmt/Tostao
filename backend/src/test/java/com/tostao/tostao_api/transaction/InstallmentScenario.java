package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * Cenário da 010. Contas {@code Corrente} (saldo inicial 500000), {@code Poupança} (0) e o cartão {@code Visa}
 * (limite 500000, fecha dia 20 e vence dia 28); categorias {@code Alimentação} e {@code Eletrônicos} (despesa) e
 * {@code Salário} (receita). Não há lançamento algum: as compras de cada teste são criadas por ele, pela API ou
 * pelo banco.
 */
public record InstallmentScenario(Fixture owner, UUID checking, UUID savings, UUID card, UUID food, UUID gadgets,
		UUID salary) {

	public static InstallmentScenario create(WorkspaceFixtures fixtures) {
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID checking = fixtures.insertAccount(w, "Corrente", "CHECKING", 500_000, false);
		UUID savings = fixtures.insertAccount(w, "Poupança", "SAVINGS", 0, false);
		UUID card = fixtures.insertCard(w, "Visa", 0, 500_000L, 20, 28, false);
		UUID food = fixtures.insertCategory(w, null, "Alimentação", "EXPENSE", false);
		UUID gadgets = fixtures.insertCategory(w, null, "Eletrônicos", "EXPENSE", false);
		UUID salary = fixtures.insertCategory(w, null, "Salário", "INCOME", false);
		return new InstallmentScenario(fx, checking, savings, card, food, gadgets, salary);
	}

	public static String purchasesUrl(UUID workspaceId) {
		return "/api/v1/workspaces/" + workspaceId + "/installment-purchases";
	}

	/** {@code GET /installment-purchases/{groupId}}; o grupo é texto para os testes de identificador malformado. */
	public static ResultActions plan(MockMvc mvc, Fixture who, Object groupId) throws Exception {
		return mvc.perform(get(purchasesUrl(who.workspaceId()) + "/" + groupId).cookie(session(who.token())));
	}

	/** {@code POST /installment-purchases} com o corpo dado. */
	public static ResultActions purchase(MockMvc mvc, Fixture who, String json) throws Exception {
		return mvc.perform(post(purchasesUrl(who.workspaceId())).cookie(session(who.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** Corpo de um registro; {@code status} nulo não aparece (a primeira parcela nasce efetivada). */
	public static String purchaseBody(UUID account, UUID category, long totalCents, int count, String firstDate,
			String description, String status) {
		String s = "{\"accountId\":\"%s\",\"categoryId\":\"%s\",\"totalCents\":%d,\"installmentCount\":%d,\"firstOccurredOn\":\"%s\",\"description\":\"%s\""
				.formatted(account, category, totalCents, count, firstDate, description);
		return s + (status == null ? "" : ",\"firstInstallmentStatus\":\"" + status + "\"") + "}";
	}

}
