package com.tostao.tostao_api.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse.AccountBalance;
import com.tostao.tostao_api.dashboard.dto.DashboardResponse.CategoryGroup;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/**
 * Camada de serviço, sozinha: o serviço roda com a conexão do dono, que ignora o RLS. Mesmo assim, o filtro
 * explícito por {@code workspace_id} das consultas (e das junções) isola os workspaces.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class DashboardServiceFilterTest {

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private void assertIsolated(DashboardService service, DashboardScenario mine, DashboardScenario other,
			Period period, long expectedExpense) {
		DashboardResponse response = service.dashboard(mine.owner().workspaceId(), period);

		assertThat(response.totals().expenseCents()).isEqualTo(expectedExpense);
		List<UUID> categoryIds = response.expenseByCategory().stream().flatMap(g -> java.util.stream.Stream
				.concat(java.util.stream.Stream.of(g.categoryId()), g.children().stream().map(c -> c.categoryId())))
				.toList();
		assertThat(categoryIds).doesNotContain(other.food(), other.market());
		assertThat(response.expenseByCategory()).extracting(CategoryGroup::categoryId).doesNotContain(other.food());
		assertThat(response.balances().accounts()).extracting(AccountBalance::id)
				.containsExactlyInAnyOrder(mine.checking(), mine.savings())
				.doesNotContain(other.checking(), other.savings());
	}

	@Test
	void theServiceFilterAloneIsolatesWorkspacesWithRlsBypassed() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		DashboardService service = new DashboardService(owner.client());
		DashboardScenario a = DashboardScenario.create(fixtures);
		DashboardScenario b = DashboardScenario.create(fixtures);
		// o outro workspace tem uma despesa grande na mesma semana e no mesmo mês
		fixtures.insertLedgerTransaction(b.owner().workspaceId(), b.checking(), b.owner().userId(), b.food(),
				"EXPENSE", 700_000, LocalDate.of(2026, 11, 3), "Só de B", false);

		assertIsolated(service, a, b, Period.ofMonth("2026-11"), 15_000);
		assertIsolated(service, a, b, Period.ofWeek("2026-W45"), 15_000);
		assertIsolated(service, b, a, Period.ofMonth("2026-11"), 715_000);
		assertIsolated(service, b, a, Period.ofWeek("2026-W45"), 715_000);
	}
}
