package com.tostao.tostao_api.auth;

import static com.tostao.tostao_api.support.AuthSupport.PASSWORD;
import static com.tostao.tostao_api.support.AuthSupport.credentials;
import static com.tostao.tostao_api.support.AuthSupport.login;
import static com.tostao.tostao_api.support.AuthSupport.newEmail;
import static com.tostao.tostao_api.support.AuthSupport.registerAndGetToken;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.support.AuthSupport.tokenOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LoginLogoutTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	JwtDecoder decoder;
	@Autowired
	JwtEncoder encoder;
	@Autowired
	OwnerJdbc owner;

	@Test
	void correctCredentialsOpenASessionWithoutExposingTheToken() throws Exception {
		String email = newEmail();
		registerAndGetToken(mvc, email);

		MvcResult result = login(mvc, email, PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(cookie).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/api");
		assertThat(result.getResponse().getContentAsString()).doesNotContain(tokenOf(result))
				.contains("\"email\":\"" + email + "\"");
	}

	@Test
	void unknownEmailAndWrongPasswordGetTheSameGenericAnswer() throws Exception {
		String email = newEmail();
		registerAndGetToken(mvc, email);

		MvcResult wrongPassword = login(mvc, email, "Outra-Senha-999");
		MvcResult unknownEmail = login(mvc, newEmail(), PASSWORD);

		assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
		assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);
		assertThat(unknownEmail.getResponse().getContentAsString())
				.isEqualTo(wrongPassword.getResponse().getContentAsString());
		assertThat(wrongPassword.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
	}

	@Test
	void emailWithSpacesAndUpperCaseSignsInNormally() throws Exception {
		String email = newEmail();
		registerAndGetToken(mvc, email);

		MvcResult result = login(mvc, "  " + email.toUpperCase() + " ", PASSWORD);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void passwordLongerThanBcryptLimitIsRefusedAsInvalidCredentials() throws Exception {
		String email = newEmail();
		registerAndGetToken(mvc, email);

		// 71 caracteres, mas 81 bytes em UTF-8: passa no limite de tamanho, estoura o do BCrypt
		MvcResult result = login(mvc, email, "a".repeat(60) + "á".repeat(10) + "1");

		assertThat(result.getResponse().getStatus()).isEqualTo(401);
	}

	@Test
	void authenticatedRequestRenewsTheCookie() throws Exception {
		String token = registerAndGetToken(mvc, newEmail());

		mvc.perform(get("/api/v1/me").cookie(session(token)))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.SET_COOKIE, org.hamcrest.Matchers.allOf(
						org.hamcrest.Matchers.startsWith("tostao_session="),
						org.hamcrest.Matchers.containsString("HttpOnly"))))
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
	}

	@Test
	void tokenCarriesVersionClaimAndAboutOneYearOfValidity() throws Exception {
		String email = newEmail();
		registerAndGetToken(mvc, email);

		Jwt jwt = decoder.decode(tokenOf(login(mvc, email, PASSWORD)));

		assertThat(((Number) jwt.getClaim("sv")).intValue()).isZero();
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toDays()).isEqualTo(365);
	}

	@Test
	void logoutClearsTheCookieAndAnonymousLogoutAlsoSucceeds() throws Exception {
		String token = registerAndGetToken(mvc, newEmail());

		MvcResult withSession = mvc.perform(post("/api/v1/auth/logout").cookie(session(token)))
				.andExpect(status().isNoContent()).andReturn();
		MvcResult anonymous = mvc.perform(post("/api/v1/auth/logout"))
				.andExpect(status().isNoContent()).andReturn();

		assertThat(withSession.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
		assertThat(anonymous.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
	}

	@Test
	void logoutWithAStaleCookieStillSucceeds() throws Exception {
		mvc.perform(post("/api/v1/auth/logout").cookie(session("lixo-que-nao-e-jwt")))
				.andExpect(status().isNoContent());
	}

	@Test
	void signingOutOnOneDeviceKeepsTheOtherDeviceSignedIn() throws Exception {
		String email = newEmail();
		String phone = registerAndGetToken(mvc, email);
		String laptop = tokenOf(login(mvc, email, PASSWORD));

		mvc.perform(post("/api/v1/auth/logout").cookie(session(phone))).andExpect(status().isNoContent());

		mvc.perform(get("/api/v1/me").cookie(session(laptop))).andExpect(status().isOk());
	}

	@Test
	void authorizationHeaderWithAValidTokenIsIgnored() throws Exception {
		String token = registerAndGetToken(mvc, newEmail());

		mvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
	}

	@Test
	void tokenInQueryStringIsIgnored() throws Exception {
		String token = registerAndGetToken(mvc, newEmail());

		mvc.perform(get("/api/v1/me").param("access_token", token)).andExpect(status().isUnauthorized());
	}

	@Test
	void tamperedGarbageAndExpiredCookiesAreUnauthorizedNeverServerErrors() throws Exception {
		String token = registerAndGetToken(mvc, newEmail());
		String tampered = token.substring(0, token.length() - 4) + "AAAA";
		UUID someone = owner.client().sql("select id from users limit 1").query(UUID.class).single();
		Instant past = Instant.now().minus(Duration.ofDays(2));
		JwtClaimsSet claims = JwtClaimsSet.builder().subject(someone.toString()).claim("sv", 0).issuedAt(past)
				.expiresAt(past.plus(Duration.ofDays(1))).build();
		String expired = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();

		for (String bad : new String[] { tampered, "lixo", expired }) {
			mvc.perform(get("/api/v1/me").cookie(session(bad)))
					.andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.status").value(401));
		}
	}

	@Test
	void staleCookieDoesNotBlockSigningInAgain() throws Exception {
		String email = newEmail();
		registerAndGetToken(mvc, email);

		mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
				.cookie(session("cookie-antigo-invalido")).content(credentials(email, PASSWORD)))
				.andExpect(status().isOk());
	}

}
