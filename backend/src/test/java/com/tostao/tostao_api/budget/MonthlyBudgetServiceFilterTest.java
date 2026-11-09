package com.tostao.tostao_api.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.budget.dto.MonthBudgetResponse;
import com.tostao.tostao_api.common.error.BudgetNotFoundException;
import com.tostao.tostao_api.common.error.CategoryNotFoundException;
import com.tostao.tostao_api.dashboard.Period;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/**
 * Camada de serviço, sozinha (SC-010): o serviço roda com a conexão do dono, que ignora o RLS, e sem o
 * aspecto de participação. Mesmo assim, o filtro explícito por {@code workspace_id} de cada consulta isola
 * os workspaces.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class MonthlyBudgetServiceFilterTest {

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private static void actAs(UUID userId) {
		Jwt jwt = Jwt.withTokenValue("teste").header("alg", "none").subject(userId.toString()).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}

	@Test
	void theServiceFilterAloneIsolatesWorkspacesWithRlsBypassed() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		MonthlyBudgetService service = new MonthlyBudgetService(owner.client());
		MonthlyBudgetScenario a = MonthlyBudgetScenario.create(fixtures);
		a.seedQuickstartBudgets(fixtures);
		MonthlyBudgetScenario b = MonthlyBudgetScenario.create(fixtures);
		UUID foreignLimit = fixtures.insertMonthlyBudget(b.owner().workspaceId(), MonthlyBudgetScenario.NOV, b.food(),
				777, b.owner().userId());
		fixtures.insertMonthlyBudget(b.owner().workspaceId(), MonthlyBudgetScenario.NOV, null, 888, b.owner().userId());
		fixtures.insertLedgerTransaction(b.owner().workspaceId(), b.checking(), b.owner().userId(), b.food(), "EXPENSE",
				700_000, LocalDate.of(2026, 11, 3), "Só de B", false);
		String bBefore = MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, b.owner().workspaceId())
				+ MonthlyBudgetScenario.financeFingerprint(owner, b.owner().workspaceId());
		actAs(a.owner().userId());

		// o acompanhamento de A não soma nem lista nada de B
		MonthBudgetResponse forA = service.month(a.owner().workspaceId(), Period.ofMonth("2026-11"));
		assertThat(forA.totalSpentCents()).isEqualTo(104_000);
		assertThat(forA.budgets()).hasSize(4).noneMatch(i -> i.id().equals(foreignLimit));
		MonthBudgetResponse forB = service.month(b.owner().workspaceId(), Period.ofMonth("2026-11"));
		assertThat(forB.totalSpentCents()).isEqualTo(804_000);
		assertThat(forB.budgets()).hasSize(2);

		// ids de B nunca se alcançam pelo workspace de A
		assertThatThrownBy(() -> service.update(a.owner().workspaceId(), foreignLimit, 1))
				.isInstanceOf(BudgetNotFoundException.class);
		assertThatThrownBy(() -> service.delete(a.owner().workspaceId(), foreignLimit))
				.isInstanceOf(BudgetNotFoundException.class);
		assertThatThrownBy(() -> service.define(a.owner().workspaceId(), Period.ofMonth("2026-12"), b.food(), 100))
				.isInstanceOf(CategoryNotFoundException.class);

		// a cópia de A não leva limites de B e não escreve em B
		var copied = service.copy(a.owner().workspaceId(), Period.ofMonth("2026-11"), 2);
		assertThat(copied.created()).isEqualTo(8);
		assertThat(fixtures.countMonthlyBudgets(a.owner().workspaceId())).isEqualTo(4 + 8);

		// nada foi escrito em B por nenhuma operação de A
		assertThat(MonthlyBudgetScenario.monthlyBudgetsFingerprint(owner, b.owner().workspaceId())
				+ MonthlyBudgetScenario.financeFingerprint(owner, b.owner().workspaceId())).isEqualTo(bBefore);
	}

}
