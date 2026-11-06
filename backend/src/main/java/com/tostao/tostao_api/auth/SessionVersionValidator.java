package com.tostao.tostao_api.auth;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Confere, a cada requisição, se a versão de sessão do token ainda é a do usuário no banco. Sem
 * cache, para "sair de todos os aparelhos" valer já na próxima requisição. Usuário inexistente,
 * claim ausente ou versão diferente tornam o token inválido.
 */
public class SessionVersionValidator implements OAuth2TokenValidator<Jwt> {

	private static final OAuth2Error INVALID = new OAuth2Error("invalid_token", "Sessão encerrada", null);

	private final JdbcClient jdbc;

	public SessionVersionValidator(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		Object claim = jwt.getClaim(SessionTokenService.SESSION_VERSION_CLAIM);
		if (!(claim instanceof Number tokenVersion)) {
			return OAuth2TokenValidatorResult.failure(INVALID);
		}
		UUID userId;
		try {
			userId = UUID.fromString(jwt.getSubject());
		}
		catch (RuntimeException e) {
			return OAuth2TokenValidatorResult.failure(INVALID);
		}
		Integer current = jdbc.sql("select session_version_of(:id)")
				.param("id", userId)
				.query(Integer.class)
				.optional()
				.orElse(null);
		if (current == null || current.intValue() != tokenVersion.intValue()) {
			return OAuth2TokenValidatorResult.failure(INVALID);
		}
		return OAuth2TokenValidatorResult.success();
	}

}
