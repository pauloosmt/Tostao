package com.tostao.tostao_api.workspace;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
class WorkspaceController {

	private final WorkspaceService workspaces;

	WorkspaceController(WorkspaceService workspaces) {
		this.workspaces = workspaces;
	}

	/** Dados do workspace e o papel da pessoa nele; 404 se não for membro ou se não existir. */
	@GetMapping("/{wid}")
	WorkspaceMembershipResponse get(@PathVariable("wid") UUID workspaceId, @AuthenticationPrincipal Jwt jwt) {
		return workspaces.get(workspaceId, UUID.fromString(jwt.getSubject()));
	}

}
