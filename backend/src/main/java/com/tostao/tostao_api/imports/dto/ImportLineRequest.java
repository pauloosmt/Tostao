package com.tostao.tostao_api.imports.dto;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Uma linha da confirmação, como veio da prévia. {@code amountCents} tem sinal (entrada positiva, saída negativa) e o
 * tipo do lançamento vem dele. Campos fora desta lista, como workspace, autoria, estado ou tipo, são recusados. As
 * regras que dependem de outras linhas ou do banco (intervalo de datas, valor, categoria, chave repetida) são conferidas
 * pelo serviço, que aponta a linha e o campo.
 */
public record ImportLineRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 200, message = "deve ter no máximo 200 caracteres")
		String key,

		@NotNull(message = "é obrigatório")
		LocalDate occurredOn,

		@NotBlank(message = "é obrigatório")
		@Size(max = 200, message = "deve ter no máximo 200 caracteres")
		String description,

		@NotNull(message = "é obrigatório")
		Long amountCents,

		/** Opcional: sem ela o lançamento nasce sem categoria. */
		UUID categoryId) {

	public ImportLineRequest {
		key = key == null ? null : key.strip();
		description = description == null ? null : description.strip();
	}

}
