package com.tostao.tostao_api.common.config;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

/**
 * Guard-rail de partida: a aplicação recusa subir com segredo fraco, ou com o segredo de
 * desenvolvimento quando o cookie é {@code Secure} (ou seja, em produção).
 */
@Component
public class SecurityStartupCheck {

	/** Valor de desenvolvimento do perfil "local". Nunca pode ser usado com {@code cookieSecure=true}. */
	public static final String DEV_SECRET = "dev-only-secret-change-me-dev-only-0123456789";

	public SecurityStartupCheck(SecurityProperties props) {
		String secret = props.jwtSecret();
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("JWT_SECRET precisa ter ao menos 32 bytes");
		}
		if (props.cookieSecure() && DEV_SECRET.equals(secret)) {
			throw new IllegalStateException(
					"JWT_SECRET ainda é o valor de desenvolvimento, mas o cookie está como Secure (produção)");
		}
		if (props.corsOrigin() == null || props.corsOrigin().isBlank() || props.corsOrigin().contains("*")) {
			throw new IllegalStateException("CORS_ORIGIN precisa ser uma única origem, sem curinga");
		}
		if (props.sessionDays() < 1) {
			throw new IllegalStateException("tostao.security.session-days precisa ser maior que zero");
		}
	}

}
