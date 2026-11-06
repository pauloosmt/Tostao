package com.tostao.tostao_api.transaction;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.transaction.dto.CreateTransactionRequest;
import com.tostao.tostao_api.transaction.dto.TransactionPageResponse;
import com.tostao.tostao_api.transaction.dto.TransactionResponse;
import com.tostao.tostao_api.transaction.dto.UpdateTransactionRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/workspaces/{wid}/transactions")
class TransactionController {

	private final TransactionService transactions;

	TransactionController(TransactionService transactions) {
		this.transactions = transactions;
	}

	/** Datas em ISO ({@code AAAA-MM-DD}); página e tamanho são conferidos pelo serviço, que aponta o campo. */
	@GetMapping
	TransactionPageResponse list(@PathVariable("wid") UUID workspaceId,
			@RequestParam(name = "page", defaultValue = "1") int page,
			@RequestParam(name = "size", defaultValue = "" + TransactionFilter.DEFAULT_SIZE) int size,
			@RequestParam(name = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(name = "accountId", required = false) UUID accountId,
			@RequestParam(name = "categoryId", required = false) UUID categoryId,
			@RequestParam(name = "type", required = false) TransactionType type,
			@RequestParam(name = "q", required = false) String q) {
		return transactions.list(workspaceId,
				new TransactionFilter(page, size, from, to, accountId, categoryId, type, q));
	}

	@GetMapping("/{id}")
	TransactionResponse get(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID transactionId) {
		return transactions.get(workspaceId, transactionId);
	}

	@PutMapping("/{id}")
	TransactionResponse update(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID transactionId,
			@Valid @RequestBody UpdateTransactionRequest request) {
		return transactions.update(workspaceId, transactionId, request);
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID transactionId) {
		transactions.delete(workspaceId, transactionId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping
	ResponseEntity<TransactionResponse> create(@PathVariable("wid") UUID workspaceId,
			@Valid @RequestBody CreateTransactionRequest request) {
		TransactionResponse created = transactions.create(workspaceId, request);
		URI location = URI.create("/api/v1/workspaces/" + workspaceId + "/transactions/" + created.id());
		return ResponseEntity.created(location).body(created);
	}

}
