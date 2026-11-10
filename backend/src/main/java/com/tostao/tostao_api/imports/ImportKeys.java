package com.tostao.tostao_api.imports;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A chave de importação: o que diz "este movimento do banco já entrou nesta conta". Estável (o mesmo movimento do mesmo
 * arquivo dá sempre a mesma chave) e com no máximo 200 caracteres.
 *
 * <p>
 * Com identificador do banco (o {@code FITID} do OFX ou uma coluna {@code id}) a chave é derivada dele. Sem
 * identificador, é o SHA-256 de data, valor, descrição normalizada e da ordem entre as linhas idênticas do arquivo.
 */
final class ImportKeys {

	/** Identificadores maiores que isto viram o SHA-256 deles, para a chave caber em 200 caracteres. */
	static final int MAX_RAW_IDENTIFIER = 150;

	private static final Pattern MARKS = Pattern.compile("\\p{M}+");
	private static final Pattern SPACES = Pattern.compile("\\s+");

	private ImportKeys() {
	}

	/** Minúscula, sem acentos e com sequências de espaços reduzidas a um (e sem espaços nas pontas). */
	static String normalize(String text) {
		String decomposed = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
		return SPACES.matcher(decomposed.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
	}

	/** Chave a partir do identificador do banco; nula se o identificador está em branco. */
	static String fromIdentifier(String identifier) {
		if (identifier == null) {
			return null;
		}
		String id = identifier.strip();
		if (id.isEmpty()) {
			return null;
		}
		return id.length() <= MAX_RAW_IDENTIFIER ? "id:" + id : "id:h:" + sha256(id);
	}

	/**
	 * Chave de um movimento sem identificador. {@code ordinal} conta (de 0) as linhas do arquivo com a mesma data, o
	 * mesmo valor e a mesma descrição normalizada, na ordem em que aparecem: duas compras iguais no mesmo dia têm
	 * chaves diferentes e, reimportado o arquivo, as mesmas chaves.
	 */
	static String derived(LocalDate date, long cents, String description, int ordinal) {
		return "h:" + sha256(date + "|" + cents + "|" + normalize(description) + "|" + ordinal);
	}

	private static String sha256(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			// SHA-256 faz parte de todo JDK; chegar aqui é defeito da instalação, não entrada da pessoa
			throw new IllegalStateException("SHA-256 indisponível", e);
		}
	}

}
