package com.tostao.tostao_api.account;

/** Tipos de conta aceitos. Os nomes batem com o CHECK da coluna {@code accounts.type}. */
public enum AccountType {

	CHECKING, SAVINGS, CASH, CREDIT_CARD, INVESTMENT;

	/** Limite, dia de fechamento e dia de vencimento só existem no cartão de crédito. */
	public boolean hasCardFields() {
		return this == CREDIT_CARD;
	}

}
