package com.tostao.tostao_api.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.tostao.tostao_api.common.config.SecurityProperties;

/**
 * Emite o JWT de sessão: {@code sub} (id do usuário), {@code sv} (versão de sessão), {@code iat} e
 * {@code exp}. A validade é deslizante: cada requisição autenticada recebe um token novo.
 */
@Service
public class SessionTokenService {

	public static final String SESSION_VERSION_CLAIM = "sv";

	private final JwtEncoder encoder;
	private final SecurityProperties props;
	private final Clock clock;

	public SessionTokenService(JwtEncoder encoder, SecurityProperties props, Clock clock) {
		this.encoder = encoder;
		this.props = props;
		this.clock = clock;
	}

	public String issue(UUID userId, int sessionVersion) {
		Instant now = clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject(userId.toString())
				.claim(SESSION_VERSION_CLAIM, sessionVersion)
				.issuedAt(now)
				.expiresAt(now.plus(Duration.ofDays(props.sessionDays())))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

}
