package com.tostao.tostao_api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

import com.tostao.tostao_api.common.config.SecurityProperties;

class SessionCookieTest {

	private static SessionCookieFactory factory(boolean secure) {
		return new SessionCookieFactory(new SecurityProperties("x".repeat(40), secure, "http://localhost", 365));
	}

	@Test
	void sessionCookieIsHttpOnlySameSiteLaxScopedToApiAndLastsOneYear() {
		ResponseCookie cookie = factory(false).create("jwt-value");

		assertThat(cookie.getName()).isEqualTo("tostao_session");
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getSameSite()).isEqualTo("Lax");
		assertThat(cookie.getPath()).isEqualTo("/api");
		assertThat(cookie.getMaxAge().toDays()).isEqualTo(365);
		assertThat(cookie.isSecure()).isFalse();
	}

	@Test
	void sessionCookieIsSecureWhenConfigured() {
		assertThat(factory(true).create("jwt-value").isSecure()).isTrue();
	}

	@Test
	void clearedCookieExpiresImmediatelyAndKeepsTheSameAttributes() {
		ResponseCookie cleared = factory(true).clear();

		assertThat(cleared.getValue()).isEmpty();
		assertThat(cleared.getMaxAge().isZero()).isTrue();
		assertThat(cleared.isHttpOnly()).isTrue();
		assertThat(cleared.getPath()).isEqualTo("/api");
		assertThat(cleared.isSecure()).isTrue();
	}

}
