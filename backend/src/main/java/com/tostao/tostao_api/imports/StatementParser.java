package com.tostao.tostao_api.imports;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.tostao.tostao_api.common.error.FieldValidationException;

/**
 * Transforma os bytes de um extrato (OFX ou CSV) em linhas normalizadas. O formato é reconhecido pelo <b>conteúdo</b>; o
 * arquivo é tratado só como dado (sem parser de XML, sem nada buscado de fora) e limitado a 2 MB e 1.000 movimentos. Um
 * arquivo que não dá para ler como um todo é recusado inteiro; uma linha ruim só carrega o seu problema.
 *
 * <p>
 * Nenhuma mensagem de erro cita o conteúdo do arquivo.
 */
public final class StatementParser {

	public enum Format {
		OFX, CSV, PDF
	}

	public record Parsed(Format format, List<StatementLine> lines) {
	}

	public static final int MAX_BYTES = 2 * 1024 * 1024;
	public static final int MAX_LINES = 1000;
	public static final int MAX_DESCRIPTION = 200;
	public static final LocalDate MIN_DATE = LocalDate.of(2000, 1, 1);
	public static final LocalDate MAX_DATE = LocalDate.of(2100, 12, 31);

	private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

	private StatementParser() {
	}

	public static Parsed parse(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw refuse("o arquivo está vazio");
		}
		if (bytes.length > MAX_BYTES) {
			throw refuse("o arquivo passa de 2 MB");
		}
		Format format;
		List<RawLine> raw;
		if (PdfReader.looksLikePdf(bytes)) {
			// binário: não passa pela decodificação de texto
			format = Format.PDF;
			raw = PdfReader.read(bytes);
		}
		else {
			String text = decode(bytes);
			if (text.isBlank()) {
				throw refuse("o arquivo está vazio");
			}
			if (looksLikeOfx(text)) {
				format = Format.OFX;
				raw = OfxReader.read(text);
			}
			else {
				format = Format.CSV;
				raw = CsvReader.read(text).orElseThrow(() -> refuse(
						"formato não reconhecido: envie um OFX, um CSV com as colunas data, descrição e valor ou um PDF"));
			}
		}
		if (raw.isEmpty()) {
			throw refuse("nenhum movimento encontrado no arquivo");
		}
		if (raw.size() > MAX_LINES) {
			throw refuse("o arquivo tem mais de 1.000 movimentos");
		}
		return new Parsed(format, normalize(raw));
	}

	/** UTF-8 estrito (com ou sem BOM) e, se o texto não for UTF-8 válido, Windows-1252. */
	static String decode(byte[] bytes) {
		String text;
		try {
			text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		}
		catch (CharacterCodingException e) {
			text = new String(bytes, WINDOWS_1252);
		}
		return text.startsWith("﻿") ? text.substring(1) : text;
	}

	private static boolean looksLikeOfx(String text) {
		String head = text.substring(0, Math.min(text.length(), 4096)).toUpperCase(Locale.ROOT);
		return head.stripLeading().startsWith("OFXHEADER") || head.contains("<OFX");
	}

	private static List<StatementLine> normalize(List<RawLine> raw) {
		List<StatementLine> lines = new ArrayList<>(raw.size());
		Map<String, Integer> sameMovement = new HashMap<>();
		Set<String> identifiers = new HashSet<>();
		for (RawLine line : raw) {
			List<String> problems = new ArrayList<>(line.problems());
			String description = description(line.description());
			if (description.isEmpty()) {
				problems.add("descrição vazia");
			}
			if (line.date() != null && (line.date().isBefore(MIN_DATE) || line.date().isAfter(MAX_DATE))) {
				problems.add("data fora de 2000-01-01 a 2100-12-31");
			}
			String key = null;
			if (problems.isEmpty()) {
				key = ImportKeys.fromIdentifier(line.identifier());
				if (key != null) {
					if (!identifiers.add(key)) {
						problems.add("identificador repetido");
						key = null;
					}
				}
				else {
					String movement = line.date() + "|" + line.cents() + "|" + ImportKeys.normalize(description);
					int ordinal = sameMovement.merge(movement, 1, Integer::sum) - 1;
					key = ImportKeys.derived(line.date(), line.cents(), description, ordinal);
				}
			}
			lines.add(new StatementLine(line.line(), line.date(), description, line.cents(), key, List.copyOf(problems)));
		}
		return lines;
	}

	/** Espaços repetidos reduzidos a um, sem espaços nas pontas e cortada em 200 caracteres (sem partir um par). */
	static String description(String text) {
		String clean = text == null ? "" : text.replaceAll("\\s+", " ").strip();
		if (clean.length() <= MAX_DESCRIPTION) {
			return clean;
		}
		int end = MAX_DESCRIPTION;
		if (Character.isHighSurrogate(clean.charAt(end - 1))) {
			end--;
		}
		return clean.substring(0, end).strip();
	}

	private static FieldValidationException refuse(String reason) {
		return new FieldValidationException("file", reason);
	}

}
