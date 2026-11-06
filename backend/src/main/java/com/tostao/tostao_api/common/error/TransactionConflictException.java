package com.tostao.tostao_api.common.error;

/** O estado da conta ou da categoria impede o lançamento: está arquivada (409). */
public class TransactionConflictException extends RuntimeException {

	public TransactionConflictException(String message) {
		super(message);
	}

}
