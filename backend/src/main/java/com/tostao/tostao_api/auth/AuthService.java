package com.tostao.tostao_api.auth;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.tostao.tostao_api.auth.dto.RegisterRequest;
import com.tostao.tostao_api.common.error.EmailAlreadyExistsException;
import com.tostao.tostao_api.user.MeResponse;
import com.tostao.tostao_api.user.MeService;

/**
 * Cadastro e entrada. Sem {@code @Transactional} abrangente: cada passo é uma chamada curta ao banco
 * que confirma sozinha, então um retorno "e-mail já existe" ou "senha errada" nunca aborta uma
 * transação maior.
 */
@Service
public class AuthService {

	private final JdbcClient jdbc;
	private final PasswordEncoder passwordEncoder;
	private final SessionTokenService tokens;
	private final MeService meService;

	public AuthService(JdbcClient jdbc, PasswordEncoder passwordEncoder, SessionTokenService tokens,
			MeService meService) {
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.tokens = tokens;
		this.meService = meService;
	}

	/**
	 * Cria conta, workspace pessoal e associação OWNER numa única chamada atômica ao banco. E-mail já
	 * existente é decidido pela própria inserção condicional da função, sem exceção.
	 */
	public AuthResult register(RegisterRequest request) {
		String hash = passwordEncoder.encode(request.password());

		UUID userId = jdbc.sql("select new_user_id from register_user(:email, :hash, :name)")
				.param("email", request.email())
				.param("hash", hash)
				.param("name", request.name())
				.query(UUID.class)
				.optional()
				.orElseThrow(EmailAlreadyExistsException::new);

		MeResponse me = meService.load(userId);
		return new AuthResult(me, tokens.issue(userId, 0));
	}

}
