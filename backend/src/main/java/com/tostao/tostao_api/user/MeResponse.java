package com.tostao.tostao_api.user;

import java.util.List;
import java.util.UUID;

import com.tostao.tostao_api.workspace.WorkspaceMembershipResponse;

/** Usuário autenticado e seus workspaces. Nunca carrega senha, hash ou credencial de sessão. */
public record MeResponse(UUID id, String name, String email, List<WorkspaceMembershipResponse> workspaces) {
}
