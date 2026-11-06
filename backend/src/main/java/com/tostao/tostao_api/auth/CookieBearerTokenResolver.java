package com.tostao.tostao_api.auth;

import java.util.Set;

import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Lê o token só do cookie de sessão. O header {@code Authorization}, parâmetros de formulário e query
 * string são ignorados de propósito. Nas rotas públicas o cookie também é ignorado, para que uma
 * sessão antiga ou revogada não impeça entrar de novo.
 */
public class CookieBearerTokenResolver implements BearerTokenResolver {

	private static final Set<String> PUBLIC_PATHS = Set.of(
			"/api/v1/health",
			"/api/v1/auth/register",
			"/api/v1/auth/login",
			"/api/v1/auth/logout");

	@Override
	public String resolve(HttpServletRequest request) {
		if (PUBLIC_PATHS.contains(request.getRequestURI())) {
			return null;
		}
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (SessionCookieFactory.NAME.equals(cookie.getName())
					&& cookie.getValue() != null && !cookie.getValue().isBlank()) {
				return cookie.getValue();
			}
		}
		return null;
	}

}
