package com.tostao.tostao_api.imports.dto;

import java.util.List;

/**
 * A prévia de um extrato: o formato reconhecido, as linhas na ordem do arquivo e as contagens. {@code valid} são as
 * linhas sem problema que ainda não existem na conta; {@code existing}, as que já existem; {@code invalid}, as com
 * problema.
 */
public record ImportPreview(
		String format,
		int total,
		int valid,
		int existing,
		int invalid,
		List<ImportLinePreview> lines) {
}
