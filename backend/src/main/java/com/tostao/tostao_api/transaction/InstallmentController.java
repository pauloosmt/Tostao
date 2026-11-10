package com.tostao.tostao_api.transaction;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.transaction.dto.InstallmentPlanResponse;

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

}
