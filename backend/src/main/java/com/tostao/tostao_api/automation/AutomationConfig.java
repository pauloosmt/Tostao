package com.tostao.tostao_api.automation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Liga o agendamento do Spring só quando a execução automática está ligada ({@code tostao.automation.enabled}, ligada
 * por padrão). Os testes a desligam por propriedade de sistema no {@code pom.xml}.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "tostao.automation.enabled", havingValue = "true", matchIfMissing = true)
class AutomationConfig {
}
