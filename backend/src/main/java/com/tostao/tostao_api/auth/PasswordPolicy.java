package com.tostao.tostao_api.auth;

import java.nio.charset.StandardCharsets;

/**
 * Regra de senha: 8 a 72 bytes em UTF-8 (72 é o limite real do BCrypt, que trunca em silêncio acima
 * disso), com ao menos uma letra e um número.
 */
public final class PasswordPolicy {

	public static final int MIN_BYTES = 8;
	public static final int MAX_BYTES = 72;

	private PasswordPolicy() {
	}

	public static boolean isValid(String password) {
		if (password == null) {
			return false;
		}
		int bytes = password.getBytes(StandardCharsets.UTF_8).length;
		if (bytes < MIN_BYTES || bytes > MAX_BYTES) {
			return false;
		}
		boolean letter = password.chars().anyMatch(Character::isLetter);
		boolean digit = password.chars().anyMatch(Character::isDigit);
		return letter && digit;
	}

}
