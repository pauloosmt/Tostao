package com.tostao.tostao_api.common.error;

/** O e-mail normalizado já tem conta (409). */
public class EmailAlreadyExistsException extends RuntimeException {

	public EmailAlreadyExistsException() {
		super("Já existe uma conta com este e-mail");
	}

}
