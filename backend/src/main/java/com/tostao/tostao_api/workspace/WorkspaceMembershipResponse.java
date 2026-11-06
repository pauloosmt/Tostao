package com.tostao.tostao_api.workspace;

import java.util.UUID;

/** Workspace de que o usuário faz parte, com o papel dele ali. */
public record WorkspaceMembershipResponse(UUID id, String name, String baseCurrency, String role) {
}
