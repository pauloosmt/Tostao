package com.tostao.tostao_api.budget;

import java.net.URI;
import java.time.Clock;
import java.util.UUID;

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

import com.tostao.tostao_api.budget.MonthlyBudgetService.DefineResult;
import com.tostao.tostao_api.budget.dto.CopyBudgetsResponse;
import com.tostao.tostao_api.budget.dto.CopyMonthlyBudgetsRequest;
import com.tostao.tostao_api.budget.dto.DefineMonthlyBudgetRequest;
import com.tostao.tostao_api.budget.dto.MonthBudgetResponse;
import com.tostao.tostao_api.budget.dto.MonthlyBudgetResponse;
import com.tostao.tostao_api.budget.dto.UpdateBudgetRequest;
import com.tostao.tostao_api.dashboard.Period;

import jakarta.validation.Valid;

/**
 * Limites de gasto por mês. O mês é validado aqui, antes de qualquer transação: erro de formato nunca chega
 * ao banco. Independente do orçamento semanal ({@link BudgetController}): rotas, tabela e ids próprios.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{wid}/monthly-budgets")
class MonthlyBudgetController {

	private final MonthlyBudgetService budgets;
	private final Clock clock;

	MonthlyBudgetController(MonthlyBudgetService budgets, Clock clock) {
		this.budgets = budgets;
		this.clock = clock;
	}

	/** Acompanhamento de um mês; sem {@code month}, o mês corrente. */
	@GetMapping
	MonthBudgetResponse month(@PathVariable("wid") UUID workspaceId,
			@RequestParam(name = "month", required = false) String month) {
		return budgets.month(workspaceId, Period.resolve(month, null, clock.instant()));
	}

	/** Define o limite de um escopo e mês: 201 se criou, 200 se substituiu o valor. */
	@PutMapping
	ResponseEntity<MonthlyBudgetResponse> define(@PathVariable("wid") UUID workspaceId,
			@Valid @RequestBody DefineMonthlyBudgetRequest request) {
		Period month = Period.ofMonth(request.month());
		DefineResult result = budgets.define(workspaceId, month, BudgetController.categoryOf(request.categoryId()),
				request.amountCents());
		if (!result.created()) {
			return ResponseEntity.ok(result.budget());
		}
		URI location = URI.create("/api/v1/workspaces/" + workspaceId + "/monthly-budgets/" + result.budget().id());
		return ResponseEntity.created(location).body(result.budget());
	}

	@PutMapping("/{id}")
	MonthlyBudgetResponse update(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID budgetId,
			@Valid @RequestBody UpdateBudgetRequest request) {
		return budgets.update(workspaceId, budgetId, request.amountCents());
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID budgetId) {
		budgets.delete(workspaceId, budgetId);
		return ResponseEntity.noContent().build();
	}

	/** Copia os limites de um mês para os próximos N meses, só onde ainda não há limite. */
	@PostMapping("/copy")
	CopyBudgetsResponse copy(@PathVariable("wid") UUID workspaceId,
			@Valid @RequestBody CopyMonthlyBudgetsRequest request) {
		Period from = Period.ofMonth(request.fromMonth(), "fromMonth");
		return budgets.copy(workspaceId, from, request.months());
	}

}
