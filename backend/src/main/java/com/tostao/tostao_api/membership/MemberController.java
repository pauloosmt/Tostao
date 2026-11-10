package com.tostao.tostao_api.membership;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.membership.dto.ChangeRoleRequest;
import com.tostao.tostao_api.membership.dto.MemberResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/workspaces/{wid}/members")
class MemberController {

	private final MemberService members;

	MemberController(MemberService members) {
		this.members = members;
	}

	@GetMapping
	List<MemberResponse> list(@PathVariable("wid") UUID workspaceId) {
		return members.list(workspaceId);
	}

	@PutMapping("/{userId}")
	MemberResponse changeRole(@PathVariable("wid") UUID workspaceId, @PathVariable("userId") UUID userId,
			@Valid @RequestBody ChangeRoleRequest request) {
		return members.changeRole(workspaceId, userId, request);
	}

	/** O dono remove um membro; um membro que não é dono sai (remove a si mesmo). */
	@DeleteMapping("/{userId}")
	ResponseEntity<Void> remove(@PathVariable("wid") UUID workspaceId, @PathVariable("userId") UUID userId) {
		members.remove(workspaceId, userId);
		return ResponseEntity.noContent().build();
	}

}
