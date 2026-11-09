package com.tostao.tostao_api.common.error;

/** O estado da categoria impede a operação no limite (semanal ou mensal): está arquivada (409). */
public class BudgetConflictException extends RuntimeException {

	public BudgetConflictException(String message) {
		super(message);
	}

}
