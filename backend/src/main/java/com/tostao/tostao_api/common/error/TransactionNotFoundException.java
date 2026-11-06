package com.tostao.tostao_api.common.error;

/** Lançamento inexistente, excluído ou de outro workspace (404): os casos são indistinguíveis. */
public class TransactionNotFoundException extends RuntimeException {

	public TransactionNotFoundException() {
		super("Lançamento não encontrado");
	}

}
