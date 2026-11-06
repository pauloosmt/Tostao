package com.tostao.tostao_api.common.error;

/**
 * O papel da pessoa no workspace (VIEWER) não permite a operação (403). Só é lançada para quem já é
 * membro, então não revela a existência de nenhum workspace.
 */
public class InsufficientRoleException extends RuntimeException {

	public InsufficientRoleException() {
		super("Seu papel neste workspace não permite esta operação");
	}

}
