package com.tostao.tostao_api.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.tostao.tostao_api.common.config.SecurityProperties;

/** Codificador e decodificador HS256 do JWT de sessão, com o validador de versão de sessão. */
@Configuration
class SessionJwtConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

	@Bean
	JwtEncoder sessionJwtEncoder(SecurityProperties props) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(key(props)));
	}

	@Bean
	JwtDecoder sessionJwtDecoder(SecurityProperties props, JdbcClient jdbc) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key(props))
				.macAlgorithm(MacAlgorithm.HS256)
				.build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
				JwtValidators.createDefault(), new SessionVersionValidator(jdbc)));
		return decoder;
	}

	private static SecretKey key(SecurityProperties props) {
		return new SecretKeySpec(props.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
	}

}
