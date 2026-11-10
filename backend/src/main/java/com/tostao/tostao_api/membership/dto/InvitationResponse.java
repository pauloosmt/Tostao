package com.tostao.tostao_api.membership.dto;

import java.time.Instant;
import java.util.UUID;

/** Um convite pendente como a lista o entrega. Nunca traz o código (nem a impressão dele). */
public record InvitationResponse(
		UUID id,
		String email,
		String role,
		UUID createdBy,
		Instant createdAt,
		Instant expiresAt) {
}
