package com.tostao.tostao_api.common.error;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Respostas 401 e 403 do Spring Security em Problem Details, sem o header
 * {@code WWW-Authenticate: Bearer} (a credencial é o cookie, nunca um header).
 */
public class ProblemSecurityHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		write(response, HttpStatus.UNAUTHORIZED, "Não autenticado", "Autenticação necessária");
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		write(response, HttpStatus.FORBIDDEN, "Acesso negado", "Você não tem permissão para este recurso");
	}

	private static void write(HttpServletResponse response, HttpStatus status, String title, String detail)
			throws IOException {
		response.setStatus(status.value());
		response.setContentType("application/problem+json");
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":"
				+ status.value() + ",\"detail\":\"" + detail + "\"}");
	}

}
