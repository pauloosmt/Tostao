package com.tostao.tostao_api.membership.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Mudança de papel de um membro. O papel chega como texto para o serviço apontar o campo {@code role} também quando o
 * valor é {@code OWNER} (que nunca se dá) ou outro desconhecido; só {@code EDITOR} e {@code VIEWER} são aceitos. Campos
 * fora desta lista são recusados.
 */
public record ChangeRoleRequest(
		@NotNull(message = "é obrigatório")
		String role) {
}
