package com.tostao.tostao_api.common.error;

/**
 * A conta existe, mas não serve para fatura: não é cartão de crédito, ou o cartão não tem dia de fechamento
 * e de vencimento (409). A mensagem diz o motivo e o que falta.
 */
public class InvoiceNotAvailableException extends RuntimeException {

	public InvoiceNotAvailableException(String message) {
		super(message);
	}

}
