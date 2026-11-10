package com.tostao.tostao_api.imports.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Confirmação da importação: de 1 a 1.000 linhas, na ordem em que vieram da prévia. */
public record ImportConfirmRequest(
		@NotNull(message = "é obrigatório")
		@Size(min = 1, max = 1000, message = "deve ter de 1 a 1.000 linhas")
		List<@Valid ImportLineRequest> lines) {
}
