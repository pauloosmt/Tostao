package com.tostao.tostao_api.common.error;

/** Já existe uma categoria ativa com o mesmo nome entre as irmãs (409). */
public class CategoryNameAlreadyExistsException extends RuntimeException {

	public CategoryNameAlreadyExistsException() {
		super("Já existe uma categoria ativa com este nome neste nível");
	}

}
