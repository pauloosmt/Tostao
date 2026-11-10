package com.tostao.tostao_api.auth;

import static com.tostao.tostao_api.support.AuthSupport.PASSWORD;
import static com.tostao.tostao_api.support.AuthSupport.login;
import static com.tostao.tostao_api.support.AuthSupport.newEmail;
import static com.tostao.tostao_api.support.AuthSupport.register;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.tostao.tostao_api.TestcontainersConfiguration;

/** SC-006: e-mail inexistente e senha errada custam o mesmo (ambos pagam o BCrypt). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LoginTimingTest {

	private static final int ATTEMPTS = 20;
	private static final int WARMUP = 5;
	/**
	 * 5 falhas seguidas bloqueiam o e-mail por 15 minutos, e o login bloqueado responde antes de qualquer BCrypt.
	 * Para o teste medir o custo da senha errada (e não o do bloqueio), as tentativas se espalham por estes
	 * e-mails, cada um com no máximo 4 falhas.
	 */
	private static final int ACCOUNTS = 8;

	@Autowired
	MockMvc mvc;

	@Test
	void unknownEmailAndWrongPasswordTakeAboutTheSameTime() throws Exception {
		String[] emails = new String[ACCOUNTS];
		for (int i = 0; i < ACCOUNTS; i++) {
			emails[i] = newEmail();
			register(mvc, emails[i]);
		}
		// aquecimento: classes carregadas, pool de conexões aberto, JIT
		for (int i = 0; i < WARMUP; i++) {
			login(mvc, emails[i % ACCOUNTS], "Errada-123-abc");
			login(mvc, newEmail(), PASSWORD);
		}

		long wrongPassword = 0;
		long unknownEmail = 0;
		for (int i = 0; i < ATTEMPTS; i++) {
			long t0 = System.nanoTime();
			login(mvc, emails[(WARMUP + i) % ACCOUNTS], "Errada-123-abc");
			wrongPassword += System.nanoTime() - t0;

			String other = newEmail();
			long t1 = System.nanoTime();
			login(mvc, other, PASSWORD);
			unknownEmail += System.nanoTime() - t1;
		}

		double wrongAvgMs = wrongPassword / (double) ATTEMPTS / 1_000_000;
		double unknownAvgMs = unknownEmail / (double) ATTEMPTS / 1_000_000;
		assertThat(Math.abs(wrongAvgMs - unknownAvgMs))
				.as("diferença média em ms (senha errada %.1f, e-mail inexistente %.1f)", wrongAvgMs, unknownAvgMs)
				.isLessThan(100.0);
	}

}
