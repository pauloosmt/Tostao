package com.tostao.tostao_api.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.common.error.TransactionNotFoundException;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;
import com.tostao.tostao_api.transaction.dto.TransactionResponse;

/**
 * Camada de serviço, sozinha: o serviço roda com a conexão do dono, que ignora o RLS. Mesmo assim, o filtro
 * explícito por {@code workspace_id} das consultas isola os workspaces.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransactionServiceFilterTest {

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private static TransactionFilter all() {
		return new TransactionFilter(1, 100, null, null, null, null, null, null);
	}

	@Test
	void theServiceFilterAloneIsolatesWorkspacesWithRlsBypassed() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		TransactionService service = new TransactionService(owner.client());
		Fixture a = fixtures.workspaceWithOwner();
		Fixture b = fixtures.workspaceWithOwner();
		UUID accountA = fixtures.insertAccount(a.workspaceId(), "Conta A", "CHECKING", 0, false);
		UUID accountB = fixtures.insertAccount(b.workspaceId(), "Conta B", "CHECKING", 0, false);
		UUID categoryA = fixtures.insertCategory(a.workspaceId(), null, "Cat A", "EXPENSE", false);
		UUID categoryB = fixtures.insertCategory(b.workspaceId(), null, "Cat B", "EXPENSE", false);
		LocalDate day = LocalDate.of(2026, 11, 5);
		UUID txA = fixtures.insertLedgerTransaction(a.workspaceId(), accountA, a.userId(), categoryA, "EXPENSE", 100,
				day, "De A", false);
		UUID txB = fixtures.insertLedgerTransaction(b.workspaceId(), accountB, b.userId(), categoryB, "EXPENSE", 200,
				day, "De B", false);
		fixtures.insertLedgerTransaction(b.workspaceId(), accountB, b.userId(), categoryB, "EXPENSE", 300, day,
				"Excluido de B", true);

		assertThat(service.get(a.workspaceId(), txA).description()).isEqualTo("De A");
		assertThat(service.get(b.workspaceId(), txB).description()).isEqualTo("De B");
		assertThatThrownBy(() -> service.get(a.workspaceId(), txB)).isInstanceOf(TransactionNotFoundException.class);
		assertThatThrownBy(() -> service.get(b.workspaceId(), txA)).isInstanceOf(TransactionNotFoundException.class);
		assertThatThrownBy(() -> service.get(a.workspaceId(), UUID.randomUUID()))
				.isInstanceOf(TransactionNotFoundException.class);

		assertThat(service.list(a.workspaceId(), all()).items()).extracting(TransactionResponse::description)
				.containsExactly("De A");
		assertThat(service.list(b.workspaceId(), all()).items()).extracting(TransactionResponse::description)
				.containsExactly("De B");
		// ids de B nos filtros de A nunca trazem dado de B
		assertThat(service.list(a.workspaceId(),
				new TransactionFilter(1, 100, null, null, accountB, null, null, null)).totalItems()).isZero();
		assertThat(service.list(a.workspaceId(),
				new TransactionFilter(1, 100, null, null, null, categoryB, null, null)).totalItems()).isZero();
	}

}
