package com.tostao.tostao_api.membership;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * O código secreto de um convite e a sua impressão. O código tem 256 bits (32 bytes de {@link SecureRandom}) em Base64
 * URL-safe sem preenchimento: 43 caracteres. Ele aparece só na resposta que cria o convite; o banco guarda apenas o
 * SHA-256 em hexadecimal (64 caracteres), com índice único. Um hash lento (como o de senha) não traria segurança
 * aqui: o código é aleatório e tem entropia de sobra, não há dicionário a retardar, e a busca por índice único dá o
 * aceite em tempo constante para qualquer entrada.
 */
public final class InvitationCodes {

	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int BYTES = 32;

	private InvitationCodes() {
	}

	/** Um código novo: 32 bytes aleatórios em Base64 URL-safe sem preenchimento (43 caracteres). */
	public static String generate() {
		byte[] bytes = new byte[BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** A impressão do código: SHA-256 em hexadecimal minúsculo (64 caracteres). Entrada qualquer tem impressão. */
	public static String hash(String code) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(code).getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException e) {
			// SHA-256 existe em toda JVM: chegar aqui é um defeito de instalação, não um caso de uso
			throw new IllegalStateException("SHA-256 indisponível", e);
		}
	}

}
