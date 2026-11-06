package com.tostao.tostao_api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailNormalizerTest {

	@Test
	void trimsAndLowercases() {
		assertThat(EmailNormalizer.normalize("  Paulo@Example.COM  ")).isEqualTo("paulo@example.com");
	}

	@Test
	void usesRootLocaleSoTurkishIDoesNotBreakTheMatch() {
		assertThat(EmailNormalizer.normalize("INFO@EXAMPLE.COM")).isEqualTo("info@example.com");
	}

	@Test
	void keepsNullAsNull() {
		assertThat(EmailNormalizer.normalize(null)).isNull();
	}

}
