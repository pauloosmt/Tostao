package com.tostao.tostao_api.common.error;

/**
 * Convite pendente inexistente no workspace, de outro workspace, já usado, já revogado ou com identificador malformado
 * (404): os casos são indistinguíveis.
 */
public class InvitationNotFoundException extends RuntimeException {

	public InvitationNotFoundException() {
		super("Convite não encontrado");
	}

}
