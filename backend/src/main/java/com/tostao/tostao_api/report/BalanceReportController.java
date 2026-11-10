package com.tostao.tostao_api.report;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.account.AccountService;
import com.tostao.tostao_api.account.dto.AccountResponse;

@RestController
@RequestMapping("/api/v1/workspaces/{wid}/reports")
class BalanceReportController {

	private final AccountService accounts;

	BalanceReportController(AccountService accounts) {
		this.accounts = accounts;
	}

	/**
	 * Saldo das contas ativas e o total. As contas e o total vêm da mesma consulta; o total é somado
	 * aqui em memória, com {@code addExact} para nunca estourar em silêncio.
	 */
	@GetMapping("/balance")
	BalanceReportResponse balance(@PathVariable("wid") UUID workspaceId) {
		List<AccountResponse> active = accounts.list(workspaceId, false);
		List<BalanceReportResponse.Item> items = active.stream()
				.map(a -> new BalanceReportResponse.Item(a.id(), a.name(), a.type(), a.balanceCents(), a.pendingCents(),
						a.projectedBalanceCents()))
				.toList();
		long total = 0;
		long totalProjected = 0;
		for (AccountResponse account : active) {
			total = Math.addExact(total, account.balanceCents());
			totalProjected = Math.addExact(totalProjected, account.projectedBalanceCents());
		}
		return new BalanceReportResponse(items, total, totalProjected);
	}

}
