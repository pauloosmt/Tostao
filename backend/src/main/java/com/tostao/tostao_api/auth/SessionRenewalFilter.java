package com.tostao.tostao_api.auth;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Janela deslizante: a cada requisição autenticada, reemite o cookie com um token novo. Roda depois
 * da autenticação. Nas rotas de saída não renova, para não disputar com o cookie apagado.
 */
public class SessionRenewalFilter extends OncePerRequestFilter {

	private static final Set<String> NO_RENEWAL = Set.of("/api/v1/auth/logout", "/api/v1/auth/logout-all");

	private final SessionTokenService tokens;
	private final SessionCookieFactory cookies;

	public SessionRenewalFilter(SessionTokenService tokens, SessionCookieFactory cookies) {
		this.tokens = tokens;
		this.cookies = cookies;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication instanceof JwtAuthenticationToken jwt && !NO_RENEWAL.contains(request.getRequestURI())) {
			UUID userId = UUID.fromString(jwt.getToken().getSubject());
			int version = ((Number) jwt.getToken().getClaim(SessionTokenService.SESSION_VERSION_CLAIM)).intValue();
			response.addHeader(HttpHeaders.SET_COOKIE, cookies.create(tokens.issue(userId, version)).toString());
		}
		chain.doFilter(request, response);
	}

}
