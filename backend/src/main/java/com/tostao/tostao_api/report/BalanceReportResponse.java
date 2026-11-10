package com.tostao.tostao_api.report;

import java.util.List;
import java.util.UUID;

import com.tostao.tostao_api.account.AccountType;

/**
 * Saldo de cada conta ativa e o total do workspace. O total é a soma das mesmas linhas listadas,
 * então lista e total sempre concordam.
 */
public record BalanceReportResponse(List<Item> accounts, long totalBalanceCents, long totalProjectedCents) {

	public record Item(UUID id, String name, AccountType type, long balanceCents, long pendingCents,
			long projectedBalanceCents) {
	}

}
