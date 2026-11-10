package com.tostao.tostao_api.automation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import com.tostao.tostao_api.TestcontainersConfiguration;

/** Nenhuma suíte roda com o agendador por baixo: o {@code pom.xml} desliga {@code tostao.automation.enabled}. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AutomationSchedulerDisabledTest {

	@Autowired
	ApplicationContext context;

	@Test
	void theSchedulerAndTheSchedulingSupportAreNotInTheTestContext() {
		assertThat(context.getBeansOfType(AutomationScheduler.class)).isEmpty();
		assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
		// o serviço, que os testes chamam com a data que querem, está sempre disponível
		assertThat(context.getBeansOfType(AutomationService.class)).hasSize(1);
	}

}
