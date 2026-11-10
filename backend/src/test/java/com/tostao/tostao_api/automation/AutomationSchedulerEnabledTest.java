package com.tostao.tostao_api.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.TestPropertySource;

import com.tostao.tostao_api.TestcontainersConfiguration;

/**
 * Com a propriedade ligada (o padrão em produção), o agendador está no contexto, agenda a execução para 10 segundos
 * depois da subida e a cada hora, e uma execução dele funciona.
 */
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "tostao.automation.enabled=true")
@SpringBootTest
class AutomationSchedulerEnabledTest {

	@Autowired
	ApplicationContext context;

	@Test
	void theSchedulerIsWiredToRunAtStartAndEveryHour() throws Exception {
		assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).hasSize(1);
		AutomationScheduler scheduler = context.getBean(AutomationScheduler.class);

		Method run = AutomationScheduler.class.getDeclaredMethod("run");
		Scheduled scheduled = run.getAnnotation(Scheduled.class);
		assertThat(scheduled).isNotNull();
		assertThat(scheduled.initialDelayString()).isEqualTo("PT10S");
		assertThat(scheduled.fixedDelayString()).isEqualTo("PT1H");
		// uma execução do agendador, direto: não propaga erro e não deixa o contexto em estado ruim
		assertThatCode(scheduler::run).doesNotThrowAnyException();
	}

}
