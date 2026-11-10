package com.tostao.tostao_api.membership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Aceite de convite. O código chega sem espaços nas pontas. Não há validação de formato de propósito: um código de
 * tamanho ou formato errado responde igual a um inexistente (404), e não de outro jeito. O limite de tamanho só impede
 * uma entrada absurda. Campos fora desta lista são recusados.
 */
public record AcceptInvitationRequest(
		@NotBlank(message = "é obrigatório")
		@Size(max = 200, message = "deve ter no máximo 200 caracteres")
		String code) {

	public AcceptInvitationRequest {
		code = code == null ? null : code.strip();
	}

	/** Nunca inclui o código: este objeto pode acabar em log. */
	@Override
	public String toString() {
		return "AcceptInvitationRequest[code=***]";
	}

}
