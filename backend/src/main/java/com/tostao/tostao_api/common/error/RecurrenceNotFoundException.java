package com.tostao.tostao_api.common.error;

/**
 * Recorrência inexistente, de outro workspace ou com identificador malformado (404): os casos são
 * indistinguíveis.
 */
public class RecurrenceNotFoundException extends RuntimeException {

	public RecurrenceNotFoundException() {
		super("Recorrência não encontrada");
	}

}
