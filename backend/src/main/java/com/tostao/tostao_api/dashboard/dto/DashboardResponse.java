package com.tostao.tostao_api.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.tostao.tostao_api.dashboard.PeriodType;

/**
 * Painel de um período (mês ou semana ISO) de um workspace. Valores em centavos inteiros; receitas e
 * despesas saem positivas e o resultado é receitas menos despesas. Percentuais têm duas casas decimais.
 * Só saída: nada disto é aceito como entrada.
 */
public record DashboardResponse(
		PeriodInfo period,
		Totals totals,
		List<CategoryGroup> incomeByCategory,
		List<CategoryGroup> expenseByCategory,
		List<DayPoint> daily,
		Comparison comparison,
		Balances balances) {

	public record PeriodInfo(PeriodType type, String id, LocalDate startDate, LocalDate endDate) {
	}

	public record Totals(long incomeCents, long expenseCents, long resultCents) {
	}

	/** Subcategoria usada no período; o percentual é sobre o total do tipo. */
	public record CategoryItem(UUID categoryId, String name, long totalCents, BigDecimal percent) {
	}

	/**
	 * Categoria principal (ou "Sem categoria", com {@code categoryId} nulo). {@code directCents} é o
	 * lançado direto nela; {@code totalCents} é {@code directCents} mais o total de todas as subcategorias.
	 */
	public record CategoryGroup(UUID categoryId, String name, long directCents, long totalCents, BigDecimal percent,
			List<CategoryItem> children) {
	}

	/** Um dia do período; {@code balanceCents} é o saldo total das contas ativas ao fim do dia. */
	public record DayPoint(LocalDate date, long incomeCents, long expenseCents, long balanceCents) {
	}

	/** Variação sobre o período anterior; {@code changePercent} é nulo quando o anterior é zero. */
	public record Change(long previousCents, long changeCents, BigDecimal changePercent) {
	}

	public record Comparison(PeriodInfo previousPeriod, Change income, Change expense, Change result) {
	}

	/** Saldo de hoje, no fim do dia anterior ao início do período e no fim do último dia. */
	public record AccountBalance(UUID id, String name, String type, long balanceCents, long openingBalanceCents,
			long closingBalanceCents) {
	}

	public record Balances(List<AccountBalance> accounts, long totalBalanceCents, long openingTotalCents,
			long closingTotalCents) {
	}

}
