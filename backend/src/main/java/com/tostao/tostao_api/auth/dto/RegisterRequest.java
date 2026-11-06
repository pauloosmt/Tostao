package com.tostao.tostao_api.auth.dto;

import com.tostao.tostao_api.auth.EmailNormalizer;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cadastro. Nome e e-mail chegam já sem espaços nas pontas (e o e-mail em minúsculas), então os
 * limites valem para o valor normalizado. Campos fora desta lista são recusados.
 */
public record RegisterRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 100, message = "deve ter no máximo 100 caracteres")
		String name,

		@NotBlank(message = "é obrigatório")
		@Email(message = "deve ser um e-mail válido")
		@Size(max = 254, message = "deve ter no máximo 254 caracteres")
		String email,

		@NotNull(message = "é obrigatória")
		@StrongPassword
		String password) {

	public RegisterRequest {
		name = name == null ? null : name.strip();
		email = EmailNormalizer.normalize(email);
	}

	/** Nunca inclui a senha: este objeto pode acabar em log. */
	@Override
	public String toString() {
		return "RegisterRequest[name=" + name + ", email=" + email + ", password=***]";
	}

}
