package com.tostao.tostao_api.common.error;

/** Conta inexistente ou de outro workspace (404): os dois casos são indistinguíveis. */
public class AccountNotFoundException extends RuntimeException {

	public AccountNotFoundException() {
		super("Conta não encontrada");
	}

}
