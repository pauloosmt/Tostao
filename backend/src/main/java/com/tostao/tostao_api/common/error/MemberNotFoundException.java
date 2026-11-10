package com.tostao.tostao_api.common.error;

/** Membro inexistente no workspace, de outro workspace ou com identificador malformado (404): os casos são indistinguíveis. */
public class MemberNotFoundException extends RuntimeException {

	public MemberNotFoundException() {
		super("Membro não encontrado");
	}

}
