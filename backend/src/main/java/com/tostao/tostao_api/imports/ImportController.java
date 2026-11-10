package com.tostao.tostao_api.imports;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.tostao.tostao_api.imports.dto.ImportPreview;

/** Importação de extratos: prévia (não grava) e, em seguida, a confirmação. */
@RestController
@RequestMapping("/api/v1/workspaces/{wid}/accounts/{accountId}/import")
class ImportController {

	private final ImportService imports;

	ImportController(ImportService imports) {
		this.imports = imports;
	}

	/**
	 * Lê o extrato (OFX ou CSV, até 2 MB) e devolve a prévia, sem gravar nada. A parte do arquivo se chama {@code file}.
	 * A participação, o papel e a conta são conferidos antes de o arquivo ser lido.
	 */
	@PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ImportPreview preview(@PathVariable("wid") UUID workspaceId, @PathVariable("accountId") UUID accountId,
			@RequestPart("file") MultipartFile file) {
		return imports.preview(workspaceId, accountId, bytesOf(file));
	}

	private static byte[] bytesOf(MultipartFile file) {
		try {
			return file.getBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
