package com.tostao.tostao_api.imports;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Os arquivos de extrato de teste em {@code src/test/resources/statements}. */
public final class Statements {

	private Statements() {
	}

	public static byte[] bytes(String name) {
		try (InputStream in = Statements.class.getResourceAsStream("/statements/" + name)) {
			if (in == null) {
				throw new IllegalArgumentException("arquivo de teste inexistente: " + name);
			}
			return in.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static String text(String name) {
		return new String(bytes(name), StandardCharsets.UTF_8);
	}

}
