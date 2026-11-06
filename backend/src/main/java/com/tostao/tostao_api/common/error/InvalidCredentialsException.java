package com.tostao.tostao_api.common.error;

/**
 * Entrada recusada (401). A mensagem é única para e-mail inexistente e senha errada, para não revelar
 * quais e-mails têm conta.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("E-mail ou senha inválidos");
	}

}
