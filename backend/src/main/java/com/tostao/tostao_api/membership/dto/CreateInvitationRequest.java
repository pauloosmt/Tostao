package com.tostao.tostao_api.membership.dto;

import com.tostao.tostao_api.auth.EmailNormalizer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Convite. O e-mail chega já normalizado (sem espaços nas pontas e em minúsculas, como no cadastro), então os limites
 * valem para o valor normalizado. O papel chega como texto para o serviço apontar o campo {@code role} também com
 * {@code OWNER} (que nunca se convida) ou outro valor; só {@code EDITOR} e {@code VIEWER} são aceitos. Campos fora desta
 * lista, como o workspace, quem convidou ou o código, são recusados.
 */
public record CreateInvitationRequest(
		@NotBlank(message = "é obrigatório")
		@Email(message = "deve ser um e-mail válido")
		@Size(max = 254, message = "deve ter no máximo 254 caracteres")
		String email,

		@NotNull(message = "é obrigatório")
		String role) {

	public CreateInvitationRequest {
		email = EmailNormalizer.normalize(email);
	}

}
