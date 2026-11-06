package com.tostao.tostao_api.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.tostao.tostao_api.auth.dto.LoginRequest;
import com.tostao.tostao_api.auth.dto.RegisterRequest;
import com.tostao.tostao_api.common.error.EmailAlreadyExistsException;
import com.tostao.tostao_api.common.error.InvalidCredentialsException;
import com.tostao.tostao_api.common.error.TooManyAttemptsException;
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
	private final LoginThrottle throttle;
	/** Hash fictício: e-mail inexistente também paga o custo do BCrypt, para o tempo não denunciar. */
	private final String dummyHash;

	public AuthService(JdbcClient jdbc, PasswordEncoder passwordEncoder, SessionTokenService tokens,
			MeService meService, LoginThrottle throttle) {
		this.jdbc = jdbc;
		this.passwordEncoder = passwordEncoder;
		this.tokens = tokens;
		this.meService = meService;
		this.throttle = throttle;
		this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
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

	/**
	 * Entrada com e-mail e senha. Falha de senha é retorno normal (exceção de domínio, sem transação
	 * aberta), com a mesma resposta para e-mail inexistente e senha errada.
	 */
	public AuthResult login(LoginRequest request) {
		// bloqueado: recusa antes de qualquer verificação, inclusive com a senha correta
		throttle.remainingLockSeconds(request.email()).ifPresent(seconds -> {
			throw new TooManyAttemptsException(seconds);
		});

		var user = jdbc.sql("select id, password_hash, session_version from find_user_for_login(:email)")
				.param("email", request.email())
				.query((rs, i) -> new LoginUser(rs.getObject("id", UUID.class), rs.getString("password_hash"),
						rs.getInt("session_version")))
				.optional();

		boolean tooLongForBcrypt = request.password().getBytes(StandardCharsets.UTF_8).length > PasswordPolicy.MAX_BYTES;
		String hash = user.map(LoginUser::passwordHash).orElse(dummyHash);
		boolean passwordMatches = !tooLongForBcrypt && passwordEncoder.matches(request.password(), hash);
		if (tooLongForBcrypt) {
			// paga o mesmo custo de uma verificação real
			passwordEncoder.matches("senha-fictícia-1", dummyHash);
		}
		if (user.isEmpty() || !passwordMatches) {
			// e-mail inexistente também conta, para o comportamento ser o mesmo de um e-mail existente
			throttle.recordFailure(request.email());
			throw new InvalidCredentialsException();
		}

		throttle.reset(request.email());
		LoginUser found = user.get();
		MeResponse me = meService.load(found.id());
		return new AuthResult(me, tokens.issue(found.id(), found.sessionVersion()));
	}

	/** "Sair de todos os aparelhos": soma 1 à versão e invalida todos os tokens anteriores. */
	public void logoutAll(UUID userId) {
		jdbc.sql("select revoke_sessions(:id)").param("id", userId).query(Integer.class).single();
	}

	private record LoginUser(UUID id, String passwordHash, int sessionVersion) {
	}

}
