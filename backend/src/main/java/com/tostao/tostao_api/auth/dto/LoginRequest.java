package com.tostao.tostao_api.auth.dto;

import com.tostao.tostao_api.auth.EmailNormalizer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Entrada. O e-mail chega normalizado; campos fora desta lista são recusados. */
public record LoginRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 254, message = "deve ter no máximo 254 caracteres")
		String email,

		@NotNull(message = "é obrigatória")
		@Size(max = 72, message = "deve ter no máximo 72 caracteres")
		String password) {

	public LoginRequest {
		email = EmailNormalizer.normalize(email);
	}

	/** Nunca inclui a senha: este objeto pode acabar em log. */
	@Override
	public String toString() {
		return "LoginRequest[email=" + email + ", password=***]";
	}

}
