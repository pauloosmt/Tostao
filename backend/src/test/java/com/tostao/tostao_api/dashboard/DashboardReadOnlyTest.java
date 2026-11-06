package com.tostao.tostao_api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/** SC-010: consultar o painel não altera nenhum dado, e o serviço só sabe ler. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DashboardReadOnlyTest {

	private static final Path SERVICE_SOURCE = Path.of("src", "main", "java", "com", "tostao", "tostao_api",
			"dashboard", "DashboardService.java");

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	DashboardService service;

	@Test
	void queryingThePanelChangesNothingInTheWorkspace() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		DashboardScenario s = DashboardScenario.create(fixtures);
		UUID workspace = s.owner().workspaceId();
		String before = DashboardScenario.fingerprint(owner, workspace);

		// o serviço de verdade, pelo proxy (aspecto + transação), com a pessoa da sessão
		withSession(s, () -> {
			service.dashboard(workspace, Period.ofMonth("2026-11"));
			service.dashboard(workspace, Period.ofWeek("2026-W45"));
			service.dashboard(workspace, Period.ofMonth("2027-03"));
		});

		assertThat(DashboardScenario.fingerprint(owner, workspace)).isEqualTo(before);
	}

	@Test
	void theServiceMethodIsAReadOnlyRepeatableReadTransactionScopedToTheWorkspace() throws Exception {
		Method method = DashboardService.class.getMethod("dashboard", UUID.class, Period.class);

		Transactional transactional = method.getAnnotation(Transactional.class);
		assertThat(transactional).isNotNull();
		assertThat(transactional.readOnly()).isTrue();
		assertThat(transactional.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
		WorkspaceScoped scoped = method.getAnnotation(WorkspaceScoped.class);
		assertThat(scoped).isNotNull();
		assertThat(scoped.write()).isFalse();
	}

	@Test
	void theServiceSourceHasNoWritingStatement() throws IOException {
		String source = Files.readString(SERVICE_SOURCE).toLowerCase();

		assertThat(source).doesNotContain("insert into").doesNotContain("update ").doesNotContain("delete from")
				.doesNotContain("truncate").doesNotContain("merge into").doesNotContain("set_config");
	}

	/** Roda o código com a pessoa da sessão no {@code SecurityContext}, como o filtro de JWT faria. */
	private void withSession(DashboardScenario s, Runnable action) {
		var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none")
				.subject(s.owner().userId().toString()).build();
		var authentication = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(
				jwt);
		var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		org.springframework.security.core.context.SecurityContextHolder.setContext(context);
		try {
			action.run();
		} finally {
			org.springframework.security.core.context.SecurityContextHolder.clearContext();
		}
	}

}
