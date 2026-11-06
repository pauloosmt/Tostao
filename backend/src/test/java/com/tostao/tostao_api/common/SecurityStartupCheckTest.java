package com.tostao.tostao_api.common;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.tostao.tostao_api.common.config.SecurityProperties;
import com.tostao.tostao_api.common.config.SecurityStartupCheck;

class SecurityStartupCheckTest {

	private static final String STRONG = "a-production-secret-with-more-than-32-bytes-0987654321";

	private static SecurityProperties props(String secret, boolean secure) {
		return new SecurityProperties(secret, secure, "https://app.example.com", 365);
	}

	@Test
	void refusesToStartWithDevSecretWhenCookieIsSecure() {
		assertThatThrownBy(() -> new SecurityStartupCheck(props(SecurityStartupCheck.DEV_SECRET, true)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("valor de desenvolvimento");
	}

	@Test
	void allowsDevSecretOnlyWhenCookieIsNotSecure() {
		assertThatCode(() -> new SecurityStartupCheck(props(SecurityStartupCheck.DEV_SECRET, false)))
				.doesNotThrowAnyException();
	}

	@Test
	void refusesShortOrMissingSecret() {
		assertThatThrownBy(() -> new SecurityStartupCheck(props("curto", false)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
		assertThatThrownBy(() -> new SecurityStartupCheck(props(null, false)))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void acceptsStrongSecretWithSecureCookie() {
		assertThatCode(() -> new SecurityStartupCheck(props(STRONG, true))).doesNotThrowAnyException();
	}

	@Test
	void refusesWildcardOrBlankCorsOrigin() {
		assertThatThrownBy(() -> new SecurityStartupCheck(new SecurityProperties(STRONG, true, "*", 365)))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new SecurityStartupCheck(new SecurityProperties(STRONG, true, " ", 365)))
				.isInstanceOf(IllegalStateException.class);
	}

}
