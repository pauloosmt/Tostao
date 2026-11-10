package com.tostao.tostao_api.transaction;

import java.util.UUID;

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

}
