package com.tostao.tostao_api.common.error;

/**
 * Limite de orçamento (semanal ou mensal) inexistente ou de outro workspace (404): os dois casos são
 * indistinguíveis. Um limite de um tipo nunca é encontrado pelas rotas do outro.
 */
public class BudgetNotFoundException extends RuntimeException {

	public BudgetNotFoundException() {
		super("Limite não encontrado");
	}

}
