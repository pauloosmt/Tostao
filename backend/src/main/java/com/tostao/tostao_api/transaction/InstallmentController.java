package com.tostao.tostao_api.transaction;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.transaction.dto.CancelInstallmentsResponse;
import com.tostao.tostao_api.transaction.dto.CreateInstallmentPurchaseRequest;
import com.tostao.tostao_api.transaction.dto.InstallmentPlanResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/workspaces/{wid}/installment-purchases")
class InstallmentController {

	private final InstallmentService installments;

	InstallmentController(InstallmentService installments) {
		this.installments = installments;
	}

	@GetMapping("/{groupId}")
	InstallmentPlanResponse plan(@PathVariable("wid") UUID workspaceId, @PathVariable("groupId") UUID groupId) {
		return installments.plan(workspaceId, groupId);
	}

	/** Registra a compra e devolve o plano, com o caminho da consulta em {@code Location}. */
	@PostMapping
	ResponseEntity<InstallmentPlanResponse> create(@PathVariable("wid") UUID workspaceId,
			@Valid @RequestBody CreateInstallmentPurchaseRequest request) {
		InstallmentPlanResponse plan = installments.create(workspaceId, request);
		URI location = URI.create("/api/v1/workspaces/" + workspaceId + "/installment-purchases/" + plan.groupId());
		return ResponseEntity.created(location).body(plan);
	}

	/** Cancela as parcelas ainda pendentes; sem pendentes, ou repetido, responde 200 com zero. */
	@PostMapping("/{groupId}/cancel")
	CancelInstallmentsResponse cancel(@PathVariable("wid") UUID workspaceId, @PathVariable("groupId") UUID groupId) {
		return installments.cancel(workspaceId, groupId);
	}

}
