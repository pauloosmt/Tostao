package com.tostao.tostao_api.user;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
class MeController {

	private final MeService me;

	MeController(MeService me) {
		this.me = me;
	}

	/** Dados da pessoa autenticada e dos workspaces de que ela faz parte. */
	@GetMapping
	MeResponse get(@AuthenticationPrincipal Jwt jwt) {
		return me.load(UUID.fromString(jwt.getSubject()));
	}

}
