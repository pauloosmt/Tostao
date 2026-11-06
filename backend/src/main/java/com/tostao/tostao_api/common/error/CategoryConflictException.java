package com.tostao.tostao_api.common.error;

/** Estado da categoria impede a operação: arquivada, categoria principal arquivada (409). */
public class CategoryConflictException extends RuntimeException {

	public CategoryConflictException(String message) {
		super(message);
	}

}
