package com.tostao.tostao_api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.workspace.TenantSession;

/**
 * Camada de dados, sozinha: as quatro consultas do painel rodam como {@code tostao_app} (a role da
 * aplicação, que não ignora o RLS) pedindo, de propósito, o workspace errado no filtro. Quem barra é o RLS.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DashboardRlsTest {

	private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
	private static final LocalDate TO = LocalDate.of(2026, 11, 30);

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	JdbcClient appJdbc;
	@Autowired
	TenantSession tenant;
	@Autowired
	PlatformTransactionManager transactionManager;

	private enum Mode {
		/** Contexto de usuário e de workspace. */
		WORKSPACE,
		/** Só o usuário, sem workspace. */
		USER_ONLY,
		/** Nenhum contexto. */
		NONE
	}

	/** Linhas devolvidas pela consulta, rodada como {@code tostao_app} com o contexto dado. */
	private long rows(String sql, Mode mode, UUID contextUser, UUID contextWorkspace, UUID askedWorkspace) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			if (mode == Mode.WORKSPACE) {
				tenant.enterWorkspace(contextUser, contextWorkspace);
			} else if (mode == Mode.USER_ONLY) {
				tenant.enterUser(contextUser);
			}
			return (long) appJdbc.sql(sql)
					.param("workspaceId", askedWorkspace)
					.param("from", FROM).param("to", TO)
					.param("start", FROM).param("end", TO)
					.query((rs, i) -> 1).list().size();
		});
	}

	private static final String[] QUERIES = { DashboardService.DAILY_SQL, DashboardService.CATEGORY_SQL,
			DashboardService.ACCOUNT_BALANCES_SQL, DashboardService.MOVEMENT_SQL };

	@Test
	void withTheRightContextEachQuerySeesTheWorkspacesOwnRows() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		DashboardScenario a = DashboardScenario.create(fixtures);

		for (String sql : QUERIES) {
			assertThat(rows(sql, Mode.WORKSPACE, a.owner().userId(), a.owner().workspaceId(), a.owner().workspaceId()))
					.as(sql).isPositive();
		}
	}

	@Test
	void askingForAnotherWorkspaceInTheFilterReturnsNothingBecauseOfRls() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		DashboardScenario a = DashboardScenario.create(fixtures);
		DashboardScenario b = DashboardScenario.create(fixtures);

		for (String sql : QUERIES) {
			assertThat(rows(sql, Mode.WORKSPACE, a.owner().userId(), a.owner().workspaceId(), b.owner().workspaceId()))
					.as(sql).isZero();
			assertThat(rows(sql, Mode.WORKSPACE, b.owner().userId(), b.owner().workspaceId(), a.owner().workspaceId()))
					.as(sql).isZero();
		}
	}

	@Test
	void withoutAWorkspaceContextTheQueriesSeeNothing() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		DashboardScenario a = DashboardScenario.create(fixtures);

		for (String sql : QUERIES) {
			assertThat(rows(sql, Mode.USER_ONLY, a.owner().userId(), null, a.owner().workspaceId())).as(sql).isZero();
			assertThat(rows(sql, Mode.NONE, null, null, a.owner().workspaceId())).as(sql).isZero();
		}
	}

}
