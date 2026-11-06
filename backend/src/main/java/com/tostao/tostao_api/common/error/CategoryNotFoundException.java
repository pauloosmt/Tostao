package com.tostao.tostao_api.common.error;

/** Categoria inexistente ou de outro workspace (404): os dois casos são indistinguíveis. */
public class CategoryNotFoundException extends RuntimeException {

	public CategoryNotFoundException() {
		super("Categoria não encontrada");
	}

}
