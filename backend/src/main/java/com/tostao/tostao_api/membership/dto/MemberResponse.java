package com.tostao.tostao_api.membership.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Um membro do workspace como a API o entrega: quem é, com que papel e desde quando. Nunca traz senha, hash de senha,
 * versão de sessão nem dado de outro workspace.
 */
public record MemberResponse(
		UUID userId,
		String name,
		String email,
		String role,
		Instant joinedAt) {
}
