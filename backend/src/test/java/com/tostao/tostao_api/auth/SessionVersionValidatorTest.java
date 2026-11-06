package com.tostao.tostao_api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwsHeader;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SessionVersionValidatorTest {

	@Autowired
	SessionTokenService tokens;
	@Autowired
	JwtDecoder decoder;
	@Autowired
	JwtEncoder encoder;
	@Autowired
	OwnerJdbc owner;

	private UUID newUser() {
		String email = "validator-" + UUID.randomUUID() + "@example.com";
		return owner.client()
				.sql("insert into users (email, password_hash, name) values (:e, 'x', 'Teste') returning id")
				.param("e", email).query(UUID.class).single();
	}

	@Test
	void tokenCarriesSubjectVersionAndAboutOneYearOfValidity() {
		UUID id = newUser();

		Jwt jwt = decoder.decode(tokens.issue(id, 0));

		assertThat(jwt.getSubject()).isEqualTo(id.toString());
		assertThat(((Number) jwt.getClaim("sv")).intValue()).isZero();
		Duration validity = Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt());
		assertThat(validity.toDays()).isEqualTo(365);
	}

	@Test
	void tokenStopsWorkingWhenTheSessionVersionChanges() {
		UUID id = newUser();
		String token = tokens.issue(id, 0);
		assertThat(decoder.decode(token)).isNotNull();

		owner.client().sql("update users set session_version = session_version + 1 where id = :id")
				.param("id", id).update();

		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
		assertThat(decoder.decode(tokens.issue(id, 1))).isNotNull();
	}

	@Test
	void tokenForUnknownUserIsRejected() {
		String token = tokens.issue(UUID.randomUUID(), 0);

		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void tokenWithoutVersionClaimIsRejected() {
		UUID id = newUser();
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().subject(id.toString()).issuedAt(now)
				.expiresAt(now.plus(Duration.ofDays(1))).build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();

		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void expiredTokenIsRejected() {
		UUID id = newUser();
		Instant past = Instant.now().minus(Duration.ofDays(2));
		JwtClaimsSet claims = JwtClaimsSet.builder().subject(id.toString()).claim("sv", 0).issuedAt(past)
				.expiresAt(past.plus(Duration.ofDays(1))).build();
		String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();

		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void tamperedTokenIsRejected() {
		UUID id = newUser();
		String token = tokens.issue(id, 0);
		String tampered = token.substring(0, token.length() - 4) + "AAAA";

		assertThatThrownBy(() -> decoder.decode(tampered)).isInstanceOf(JwtException.class);
	}

}
