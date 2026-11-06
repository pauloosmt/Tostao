package com.tostao.tostao_api.common.error;

/** Regra de validação que depende de mais de um campo, apontando o campo culpado (400). */
public class FieldValidationException extends RuntimeException {

	private final String field;

	public FieldValidationException(String field, String message) {
		super(message);
		this.field = field;
	}

	public String field() {
		return field;
	}

}
