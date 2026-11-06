package com.tostao.tostao_api.common.error;

/** Já existe uma conta ativa com este nome no workspace (409). */
public class AccountNameAlreadyExistsException extends RuntimeException {

	public AccountNameAlreadyExistsException() {
		super("Já existe uma conta ativa com este nome");
	}

}
