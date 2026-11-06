package com.tostao.tostao_api.transaction;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.transaction.dto.CreateTransactionRequest;
import com.tostao.tostao_api.transaction.dto.TransactionResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/workspaces/{wid}/transactions")
class TransactionController {

	private final TransactionService transactions;

	TransactionController(TransactionService transactions) {
		this.transactions = transactions;
	}

	@PostMapping
	ResponseEntity<TransactionResponse> create(@PathVariable("wid") UUID workspaceId,
			@Valid @RequestBody CreateTransactionRequest request) {
		TransactionResponse created = transactions.create(workspaceId, request);
		URI location = URI.create("/api/v1/workspaces/" + workspaceId + "/transactions/" + created.id());
		return ResponseEntity.created(location).body(created);
	}

}
