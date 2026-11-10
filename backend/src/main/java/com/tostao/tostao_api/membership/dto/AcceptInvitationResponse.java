package com.tostao.tostao_api.membership.dto;

import java.util.UUID;

/** O workspace em que a pessoa passou a entrar e o papel que recebeu. */
public record AcceptInvitationResponse(
		UUID workspaceId,
		String name,
		String role) {
}
