package com.tostao.tostao_api.budget;

import java.net.URI;
import java.time.Clock;
import java.util.UUID;
import java.util.regex.Pattern;

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

import com.tostao.tostao_api.budget.BudgetService.DefineResult;
import com.tostao.tostao_api.budget.dto.BudgetResponse;
import com.tostao.tostao_api.budget.dto.CopyBudgetsRequest;
import com.tostao.tostao_api.budget.dto.CopyBudgetsResponse;
import com.tostao.tostao_api.budget.dto.DefineBudgetRequest;
import com.tostao.tostao_api.budget.dto.UpdateBudgetRequest;
import com.tostao.tostao_api.budget.dto.WeekBudgetResponse;
import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.dashboard.Period;

import jakarta.validation.Valid;

/**
 * Limites de gasto por semana. A semana é validada aqui, antes de qualquer transação: erro de formato
 * nunca chega ao banco.
 */
@RestController
@RequestMapping("/api/v1/workspaces/{wid}/budgets")
class BudgetController {

	private static final Pattern UUID_FORMAT = Pattern
			.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private final BudgetService budgets;
	private final Clock clock;

	BudgetController(BudgetService budgets, Clock clock) {
		this.budgets = budgets;
		this.clock = clock;
	}

	/** Acompanhamento de uma semana; sem {@code week}, a semana corrente. */
	@GetMapping
	WeekBudgetResponse week(@PathVariable("wid") UUID workspaceId,
			@RequestParam(name = "week", required = false) String week) {
		return budgets.week(workspaceId, Period.resolveWeek(week, clock.instant()));
	}

	/** Define o limite de um escopo e semana: 201 se criou, 200 se substituiu o valor. */
	@PutMapping
	ResponseEntity<BudgetResponse> define(@PathVariable("wid") UUID workspaceId,
			@Valid @RequestBody DefineBudgetRequest request) {
		Period week = Period.ofWeek(request.week());
		DefineResult result = budgets.define(workspaceId, week, categoryOf(request.categoryId()), request.amountCents());
		if (!result.created()) {
			return ResponseEntity.ok(result.budget());
		}
		URI location = URI.create("/api/v1/workspaces/" + workspaceId + "/budgets/" + result.budget().id());
		return ResponseEntity.created(location).body(result.budget());
	}

	/**
	 * Ausente ou nulo é o limite geral. Texto vazio ou que não é um UUID é recusado: o Jackson converteria
	 * uma string vazia em nulo e criaria, sem aviso, um limite geral no lugar do de categoria.
	 */
	static UUID categoryOf(String text) {
		if (text == null) {
			return null;
		}
		if (!UUID_FORMAT.matcher(text).matches()) {
			throw new FieldValidationException("categoryId", "deve ser o identificador (UUID) de uma categoria");
		}
		return UUID.fromString(text);
	}

	@PutMapping("/{id}")
	BudgetResponse update(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID budgetId,
			@Valid @RequestBody UpdateBudgetRequest request) {
		return budgets.update(workspaceId, budgetId, request.amountCents());
	}

	@DeleteMapping("/{id}")
	ResponseEntity<Void> delete(@PathVariable("wid") UUID workspaceId, @PathVariable("id") UUID budgetId) {
		budgets.delete(workspaceId, budgetId);
		return ResponseEntity.noContent().build();
	}

	/** Copia os limites de uma semana para as próximas N semanas, só onde ainda não há limite. */
	@PostMapping("/copy")
	CopyBudgetsResponse copy(@PathVariable("wid") UUID workspaceId, @Valid @RequestBody CopyBudgetsRequest request) {
		Period from = Period.ofWeek(request.fromWeek(), "fromWeek");
		return budgets.copy(workspaceId, from, request.weeks());
	}

}
