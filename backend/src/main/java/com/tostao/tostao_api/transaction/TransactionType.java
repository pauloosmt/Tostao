package com.tostao.tostao_api.transaction;

/** Receita, despesa ou transferência entre contas. O tipo nunca muda depois de criado o lançamento. */
public enum TransactionType {
	INCOME,
	EXPENSE,
	TRANSFER
}
