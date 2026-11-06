package com.tostao.tostao_api.auth;

import java.util.OptionalLong;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Limite de tentativas de entrada, guardado no banco (sobrevive a reinício). Cada método é uma
 * chamada curta e independente a uma função do banco, que confirma sozinha: a falha de senha nunca
 * fica presa a uma transação que depois seja desfeita.
 */
@Component
public class LoginThrottle {

	private final JdbcClient jdbc;

	public LoginThrottle(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Segundos até o bloqueio acabar, ou vazio se o e-mail não está bloqueado. */
	public OptionalLong remainingLockSeconds(String email) {
		Integer seconds = jdbc.sql("select ceil(extract(epoch from (login_lockout_status(:email) - now())))::int")
				.param("email", email)
				.query(Integer.class)
				.optional()
				.orElse(null);
		return seconds == null ? OptionalLong.empty() : OptionalLong.of(seconds);
	}

	/** Soma uma falha; ao chegar em 5, o banco bloqueia o e-mail por 15 minutos. */
	public void recordFailure(String email) {
		jdbc.sql("select record_login_failure(:email)")
				.param("email", email)
				.query((rs, i) -> 1)
				.list();
	}

	/** Zera o contador depois de uma entrada bem-sucedida. */
	public void reset(String email) {
		jdbc.sql("select reset_login_attempts(:email)")
				.param("email", email)
				.query((rs, i) -> 1)
				.list();
	}

}
