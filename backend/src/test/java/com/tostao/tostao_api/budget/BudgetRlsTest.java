package com.tostao.tostao_api.budget;

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
 * Camada de dados, sozinha: as consultas do orçamento rodam como {@code tostao_app} (a role da aplicação,
 * que não ignora o RLS) pedindo, de propósito, o workspace errado no filtro, e as escritas tentam alcançar
 * limites de outro workspace. Quem barra é o RLS. As restrições e os privilégios da tabela estão no
 * {@code BudgetSchemaTest}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class BudgetRlsTest {

	private static final LocalDate START = LocalDate.of(2026, 11, 2);
	private static final LocalDate END = LocalDate.of(2026, 11, 8);

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

	private long rows(String sql, Mode mode, UUID contextUser, UUID contextWorkspace, UUID askedWorkspace) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			enter(mode, contextUser, contextWorkspace);
			return (long) appJdbc.sql(sql).param("workspaceId", askedWorkspace).param("start", START).param("end", END)
					.query((rs, i) -> 1).list().size();
		});
	}

	private void enter(Mode mode, UUID user, UUID workspace) {
		if (mode == Mode.WORKSPACE) {
			tenant.enterWorkspace(user, workspace);
		} else if (mode == Mode.USER_ONLY) {
			tenant.enterUser(user);
		}
	}

	private static final String[] QUERIES = { BudgetService.WEEK_LIMITS_SQL, BudgetService.PERIOD_SPENT_SQL };

	private BudgetScenario scenario(WorkspaceFixtures fixtures) {
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);
		return s;
	}

	@Test
	void withTheRightContextEachQuerySeesTheWorkspacesOwnRows() {
		BudgetScenario a = scenario(new WorkspaceFixtures(owner, tokens));

		for (String sql : QUERIES) {
			assertThat(rows(sql, Mode.WORKSPACE, a.owner().userId(), a.owner().workspaceId(), a.owner().workspaceId()))
					.as(sql).isPositive();
		}
	}

	@Test
	void askingForAnotherWorkspaceInTheFilterReturnsNothingBecauseOfRls() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		BudgetScenario a = scenario(fixtures);
		BudgetScenario b = scenario(fixtures);

		for (String sql : QUERIES) {
			assertThat(rows(sql, Mode.WORKSPACE, a.owner().userId(), a.owner().workspaceId(), b.owner().workspaceId()))
					.as(sql).isZero();
			assertThat(rows(sql, Mode.WORKSPACE, b.owner().userId(), b.owner().workspaceId(), a.owner().workspaceId()))
					.as(sql).isZero();
		}
	}

	@Test
	void withoutAWorkspaceContextTheQueriesSeeNothing() {
		BudgetScenario a = scenario(new WorkspaceFixtures(owner, tokens));

		for (String sql : QUERIES) {
			assertThat(rows(sql, Mode.USER_ONLY, a.owner().userId(), null, a.owner().workspaceId())).as(sql).isZero();
			assertThat(rows(sql, Mode.NONE, null, null, a.owner().workspaceId())).as(sql).isZero();
		}
	}

	@Test
	void updateAndDeleteByIdNeverReachALimitOfAnotherWorkspace() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		BudgetScenario a = scenario(fixtures);
		BudgetScenario b = scenario(fixtures);
		String before = BudgetScenario.budgetsFingerprint(owner, b.owner().workspaceId());

		int updated = new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(a.owner().userId(), a.owner().workspaceId());
			// sem filtro de workspace de propósito: só o RLS protege
			return appJdbc.sql("update weekly_budgets set amount_cents = 1, updated_at = now()").update();
		});
		int deleted = new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(a.owner().userId(), a.owner().workspaceId());
			return appJdbc.sql("delete from weekly_budgets where workspace_id = :w")
					.param("w", b.owner().workspaceId()).update();
		});

		assertThat(updated).isEqualTo(4);
		assertThat(deleted).isZero();
		assertThat(BudgetScenario.budgetsFingerprint(owner, b.owner().workspaceId())).isEqualTo(before);
		assertThat(fixtures.countWeeklyBudgets(b.owner().workspaceId())).isEqualTo(4);
	}

}
