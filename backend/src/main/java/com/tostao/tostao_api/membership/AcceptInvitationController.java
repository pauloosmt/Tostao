package com.tostao.tostao_api.membership;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.membership.dto.AcceptInvitationRequest;
import com.tostao.tostao_api.membership.dto.AcceptInvitationResponse;

import jakarta.validation.Valid;

/** O aceite é a única rota da funcionalidade sem workspace: a pessoa ainda não é membro. Exige sessão. */
@RestController
@RequestMapping("/api/v1/invitations")
class AcceptInvitationController {

	private final InvitationService invitations;

	AcceptInvitationController(InvitationService invitations) {
		this.invitations = invitations;
	}

	@PostMapping("/accept")
	AcceptInvitationResponse accept(@Valid @RequestBody AcceptInvitationRequest request) {
		return invitations.accept(request.code());
	}

}
