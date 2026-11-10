package com.tostao.tostao_api.common.error;

/**
 * A operação de membros ou convites conflita com o estado atual (409): já é membro, limite de membros ou de convites,
 * mexer no dono. A mensagem diz o motivo sem revelar dado de outra pessoa.
 */
public class MembershipConflictException extends RuntimeException {

	public MembershipConflictException(String message) {
		super(message);
	}

}
