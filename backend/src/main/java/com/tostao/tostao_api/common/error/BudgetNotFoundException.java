package com.tostao.tostao_api.common.error;

/** Limite semanal inexistente ou de outro workspace (404): os dois casos são indistinguíveis. */
public class BudgetNotFoundException extends RuntimeException {

	public BudgetNotFoundException() {
		super("Limite não encontrado");
	}

}
