package com.tostao.tostao_api.common.error;

/** A conta está arquivada e só aceita restauração (409). */
public class AccountArchivedException extends RuntimeException {

	public AccountArchivedException() {
		super("A conta está arquivada; restaure-a antes de editar");
	}

}
