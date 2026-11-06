package com.tostao.tostao_api.dashboard;

import java.time.Clock;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.tostao.tostao_api.dashboard.dto.DashboardResponse;

/** Painel de um mês ou de uma semana do workspace. Somente leitura: só existe {@code GET}. */
@RestController
@RequestMapping("/api/v1/workspaces/{wid}/dashboard")
class DashboardController {

	private final DashboardService dashboard;
	private final Clock clock;

	DashboardController(DashboardService dashboard, Clock clock) {
		this.dashboard = dashboard;
		this.clock = clock;
	}

	/**
	 * O período é validado aqui, antes de qualquer transação: erro de formato nunca chega ao banco.
	 */
	@GetMapping
	DashboardResponse get(@PathVariable("wid") UUID workspaceId,
			@RequestParam(name = "month", required = false) String month,
			@RequestParam(name = "week", required = false) String week) {
		Period period = Period.resolve(month, week, clock.instant());
		return dashboard.dashboard(workspaceId, period);
	}

}
