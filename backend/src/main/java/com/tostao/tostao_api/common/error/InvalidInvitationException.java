package com.tostao.tostao_api.common.error;

/**
 * O código de convite não vale (404). Responde sempre igual para código inexistente, expirado, já usado, revogado ou de
 * outro e-mail, para ninguém descobrir quem foi convidado nem testar e-mails.
 */
public class InvalidInvitationException extends RuntimeException {

	public InvalidInvitationException() {
		super("Convite inválido ou expirado");
	}

}
