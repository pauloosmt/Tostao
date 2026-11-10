package com.tostao.tostao_api.automation;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tostao.tostao_api.automation.AutomationService.RunResult;
import com.tostao.tostao_api.dashboard.Period;

/**
 * Dispara a execução automática ao iniciar (10 segundos depois da subida) e depois a cada hora. "Hoje" é o dia no fuso
 * {@code America/Sao_Paulo}, o mesmo do dashboard e da fatura, então um lançamento que vence no dia D é efetivado em
 * até uma hora depois do início do dia D. O {@code try/catch} fica só aqui, <b>fora</b> de qualquer transação, para
 * registrar a falha e deixar a próxima hora tentar de novo; dentro da transação não há captura.
 */
@Component
@ConditionalOnProperty(name = "tostao.automation.enabled", havingValue = "true", matchIfMissing = true)
class AutomationScheduler {

	private static final Logger log = LoggerFactory.getLogger(AutomationScheduler.class);

	private final AutomationService automation;
	private final Clock clock;

	AutomationScheduler(AutomationService automation, Clock clock) {
		this.automation = automation;
		this.clock = clock;
	}

	@Scheduled(initialDelayString = "PT10S", fixedDelayString = "PT1H")
	void run() {
		try {
			RunResult result = automation.runOnce(Period.today(clock.instant()));
			if (result.skipped()) {
				log.info("Execução automática pulada: outra execução está em curso");
			}
			else {
				log.info("Execução automática: {} ocorrências geradas, {} lançamentos efetivados", result.generated(),
						result.cleared());
			}
		}
		catch (RuntimeException e) {
			log.error("Falha na execução automática; a próxima tentativa é daqui a uma hora", e);
		}
	}

}
