package com.tostao.tostao_api.common.error;

/** Sessão ausente ou que não corresponde mais a um usuário (401). */
public class UnauthenticatedException extends RuntimeException {

	public UnauthenticatedException() {
		super("Autenticação necessária");
	}

}
