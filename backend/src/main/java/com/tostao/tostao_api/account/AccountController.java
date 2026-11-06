package com.tostao.tostao_api.account;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.account.dto.AccountResponse;
import com.tostao.tostao_api.account.dto.CreateAccountRequest;
import com.tostao.tostao_api.account.dto.UpdateAccountRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/workspaces/{wid}/accounts")
class AccountController {

	private final AccountService accounts;

	AccountController(AccountService accounts) {
		this.accounts = accounts;
	}

	@GetMapping
	List<AccountResponse> list(@PathVariable("wid") UUID workspaceId,
			@RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
		return accounts.list(workspaceId, includeArchived);
	}

	@GetMapping("/{id}")
	AccountResponse get(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID accountId) {
		return accounts.get(workspaceId, accountId);
	}

	@PostMapping
	ResponseEntity<AccountResponse> create(@PathVariable("wid") UUID workspaceId,
			@Valid @RequestBody CreateAccountRequest request) {
		AccountResponse created = accounts.create(workspaceId, request);
		URI location = URI.create("/api/v1/workspaces/" + workspaceId + "/accounts/" + created.id());
		return ResponseEntity.created(location).body(created);
	}

	@PutMapping("/{id}")
	AccountResponse update(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID accountId,
			@Valid @RequestBody UpdateAccountRequest request) {
		return accounts.update(workspaceId, accountId, request);
	}

	@PostMapping("/{id}/archive")
	AccountResponse archive(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID accountId) {
		return accounts.archive(workspaceId, accountId);
	}

	@PostMapping("/{id}/restore")
	AccountResponse restore(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID accountId) {
		return accounts.restore(workspaceId, accountId);
	}

}
