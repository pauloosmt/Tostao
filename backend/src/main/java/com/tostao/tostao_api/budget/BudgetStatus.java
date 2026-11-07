package com.tostao.tostao_api.budget;

/**
 * Estado de um limite semanal. A ordem do enum é a da gravidade crescente: {@code WITHIN} (abaixo de 80% do
 * limite), {@code WARNING} (de 80% a 100%, inclusive) e {@code EXCEEDED} (acima de 100%).
 */
public enum BudgetStatus {
	WITHIN, WARNING, EXCEEDED
}
