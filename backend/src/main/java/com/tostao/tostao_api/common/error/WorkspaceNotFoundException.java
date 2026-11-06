package com.tostao.tostao_api.common.error;

/**
 * Workspace inexistente ou de outra pessoa (404). Os dois casos são indistinguíveis de propósito:
 * o sistema nunca revela a existência de um workspace alheio.
 */
public class WorkspaceNotFoundException extends RuntimeException {

	public WorkspaceNotFoundException() {
		super("Workspace não encontrado");
	}

}
