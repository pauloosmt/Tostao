package com.tostao.tostao_api.workspace;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.tostao.tostao_api.common.error.UnauthenticatedException;

/**
 * Usuário da sessão em curso. Sempre vem do {@code SecurityContext} (o JWT já validado), nunca de
 * argumento de método nem do corpo da requisição.
 */
public final class CurrentUser {

	private CurrentUser() {
	}

	public static UUID id() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken jwt)) {
			throw new UnauthenticatedException();
		}
		return UUID.fromString(jwt.getToken().getSubject());
	}

}
