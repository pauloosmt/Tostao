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
 * Camada de dados, sozinha: as consultas do orçamento mensal rodam como {@code tostao_app} (a role da aplicação,
 * que não ignora o RLS) pedindo, de propósito, o workspace errado no filtro, e as escritas tentam alcançar
 * limites de outro workspace. Quem barra é o RLS. As restrições e os privilégios da tabela estão no
 * {@code MonthlyBudgetSchemaTest}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MonthlyBudgetRlsTest {

	private static final LocalDate START = LocalDate.of(2026, 11, 1);
	private static final LocalDate END = LocalDate.of(2026, 11, 30);

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

	/** As duas consultas do acompanhamento do mês: os limites e o gasto por categoria. */
	private static final String[] QUERIES = { MonthlyBudgetService.MONTH_LIMITS_SQL, BudgetService.PERIOD_SPENT_SQL };

	private MonthlyBudgetScenario scenario(WorkspaceFixtures fixtures) {
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);
		return s;
	}

	@Test
	void withTheRightContextEachQuerySeesTheWorkspacesOwnRows() {
		MonthlyBudgetScenario a = scenario(new WorkspaceFixtures(owner, tokens));

		for (String sql : QUERIES) {
			assertThat(rows(sql, Mode.WORKSPACE, a.owner().userId(), a.owner().workspaceId(), a.owner().workspaceId()))
					.as(sql).isPositive();
		}
	}

	@Test
	void askingForAnotherWorkspaceInTheFilterReturnsNothingBecauseOfRls() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		MonthlyBudgetScenario a = scenario(fixtures);
		MonthlyBudgetScenario b = scenario(fixtures);

		for (String sql : QUERIES) {
			assertThat(rows(sql, Mode.WORKSPACE, a.owner().userId(), a.owner().workspaceId(), b.owner().workspaceId()))
					.as(sql).isZero();
			assertThat(rows(sql, Mode.WORKSPACE, b.owner().userId(), b.owner().workspaceId(), a.owner().workspaceId()))
					.as(sql).isZero();
		}
	}

	@Test
	void withoutAWorkspaceContextTheQueriesSeeNothing() {
		MonthlyBudgetScenario a = scenario(new WorkspaceFixtures(owner, tokens));

		for (String sql : QUERIES) {
			assertThat(rows(sql, Mode.USER_ONLY, a.owner().userId(), null, a.owner().workspaceId())).as(sql).isZero();
			assertThat(rows(sql, Mode.NONE, null, null, a.owner().workspaceId())).as(sql).isZero();
		}
	}

	@Test
	void updateAndDeleteNeverReachALimitOfAnotherWorkspace() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		MonthlyBudgetScenario a = scenario(fixtures);
		MonthlyBudgetScenario b = scenario(fixtures);
		String before = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, b.owner().workspaceId());

		int updated = new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(a.owner().userId(), a.owner().workspaceId());
			// sem filtro de workspace de propósito: só o RLS protege
			return appJdbc.sql("update monthly_budgets set amount_cents = 1, updated_at = now()").update();
		});
		int deleted = new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(a.owner().userId(), a.owner().workspaceId());
			return appJdbc.sql("delete from monthly_budgets where workspace_id = :w")
					.param("w", b.owner().workspaceId()).update();
		});

		// só os 4 limites de A são alcançados; os de B ficam intactos
		assertThat(updated).isEqualTo(4);
		assertThat(deleted).isZero();
		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, b.owner().workspaceId())).isEqualTo(before);
		assertThat(fixtures.countMonthlyBudgets(b.owner().workspaceId())).isEqualTo(4);
	}

	@Test
	void aViewerReadsButTheDatabaseRefusesItsWrites() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		MonthlyBudgetScenario a = scenario(fixtures);
		var viewer = fixtures.addMember(a.owner().workspaceId(), "VIEWER");
		String before = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, a.owner().workspaceId());

		long visible = rows(MonthlyBudgetService.MONTH_LIMITS_SQL, Mode.WORKSPACE, viewer.userId(),
				a.owner().workspaceId(), a.owner().workspaceId());
		int updated = new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(viewer.userId(), a.owner().workspaceId());
			return appJdbc.sql("update monthly_budgets set amount_cents = 1, updated_at = now()").update();
		});
		int deleted = new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(viewer.userId(), a.owner().workspaceId());
			return appJdbc.sql("delete from monthly_budgets").update();
		});

		assertThat(visible).isEqualTo(4);
		assertThat(updated).isZero();
		assertThat(deleted).isZero();
		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, a.owner().workspaceId())).isEqualTo(before);
	}

}
