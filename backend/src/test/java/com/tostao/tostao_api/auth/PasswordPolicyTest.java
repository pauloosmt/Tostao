package com.tostao.tostao_api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

	@Test
	void acceptsEightCharactersWithLetterAndDigit() {
		assertThat(PasswordPolicy.isValid("abcdefg1")).isTrue();
		assertThat(PasswordPolicy.isValid("1234567a")).isTrue();
		assertThat(PasswordPolicy.isValid("Senha-forte-123")).isTrue();
	}

	@Test
	void rejectsSevenCharacters() {
		assertThat(PasswordPolicy.isValid("abcdef1")).isFalse();
	}

	@Test
	void rejectsLettersOnlyAndDigitsOnly() {
		assertThat(PasswordPolicy.isValid("abcdefghij")).isFalse();
		assertThat(PasswordPolicy.isValid("1234567890")).isFalse();
	}

	@Test
	void rejectsNullAndBlank() {
		assertThat(PasswordPolicy.isValid(null)).isFalse();
		assertThat(PasswordPolicy.isValid("        ")).isFalse();
	}

	@Test
	void acceptsExactly72BytesAndRejects73() {
		String at72 = "a".repeat(71) + "1";
		String at73 = "a".repeat(72) + "1";

		assertThat(PasswordPolicy.isValid(at72)).isTrue();
		assertThat(PasswordPolicy.isValid(at73)).isFalse();
	}

	@Test
	void countsBytesNotCharacters() {
		// 40 caracteres com acento = 80 bytes em UTF-8, acima do limite do BCrypt
		String accented = "á".repeat(39) + "1";

		assertThat(accented.length()).isEqualTo(40);
		assertThat(PasswordPolicy.isValid(accented)).isFalse();
	}

}
