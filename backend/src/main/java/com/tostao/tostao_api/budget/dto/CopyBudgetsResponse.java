package com.tostao.tostao_api.budget.dto;

/** Resultado da cópia: limites gravados, ignorados por já existirem e ignorados por categoria arquivada. */
public record CopyBudgetsResponse(int created, int skippedExisting, int skippedArchivedCategory) {
}
