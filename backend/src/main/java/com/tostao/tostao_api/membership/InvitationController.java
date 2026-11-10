package com.tostao.tostao_api.membership;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.membership.dto.CreateInvitationRequest;
import com.tostao.tostao_api.membership.dto.InvitationCreatedResponse;
import com.tostao.tostao_api.membership.dto.InvitationResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/workspaces/{wid}/invitations")
class InvitationController {

	private final InvitationService invitations;

	InvitationController(InvitationService invitations) {
		this.invitations = invitations;
	}

	/** Convida e devolve o código, uma só vez. */
	@PostMapping
	ResponseEntity<InvitationCreatedResponse> create(@PathVariable("wid") UUID workspaceId,
			@Valid @RequestBody CreateInvitationRequest request) {
		InvitationCreatedResponse created = invitations.create(workspaceId, request);
		URI location = URI.create("/api/v1/workspaces/" + workspaceId + "/invitations");
		return ResponseEntity.created(location).body(created);
	}

	@GetMapping
	List<InvitationResponse> list(@PathVariable("wid") UUID workspaceId) {
		return invitations.list(workspaceId);
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> revoke(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID invitationId) {
		invitations.revoke(workspaceId, invitationId);
		return ResponseEntity.noContent().build();
	}

}
