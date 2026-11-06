package com.tostao.tostao_api.auth;

import java.util.Locale;

/** Normaliza o e-mail: sem espaços nas pontas e em minúsculas, com locale fixo. */
public final class EmailNormalizer {

	private EmailNormalizer() {
	}

	public static String normalize(String email) {
		return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}

}
