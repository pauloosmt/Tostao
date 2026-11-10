package com.tostao.tostao_api.membership.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * O convite recém-criado, com o código secreto. <b>É a única resposta do sistema que traz o código</b>: ele aparece
 * aqui uma só vez e nunca é guardado (o banco guarda só a impressão). O {@code toString} o mascara, porque este objeto
 * poderia acabar num log.
 */
public record InvitationCreatedResponse(
		UUID id,
		String email,
		String role,
		UUID createdBy,
		Instant createdAt,
		Instant expiresAt,
		String code) {

	@Override
	public String toString() {
		return "InvitationCreatedResponse[id=" + id + ", email=" + email + ", role=" + role + ", code=***]";
	}

}
