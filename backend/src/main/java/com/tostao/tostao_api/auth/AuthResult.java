package com.tostao.tostao_api.auth;

import com.tostao.tostao_api.user.MeResponse;

/** Resultado de cadastro ou entrada: os dados da pessoa e o token que vai para o cookie. */
public record AuthResult(MeResponse me, String token) {

	/** O token nunca aparece em log nem em texto. */
	@Override
	public String toString() {
		return "AuthResult[me=" + me + ", token=***]";
	}

}
