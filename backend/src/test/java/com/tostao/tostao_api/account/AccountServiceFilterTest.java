package com.tostao.tostao_api.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.account.dto.AccountResponse;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.common.error.AccountNotFoundException;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * Camada de serviço, sozinha (SC-006): o serviço roda com a conexão do dono, que ignora o RLS. Mesmo
 * assim, o filtro explícito por {@code workspace_id} das consultas isola os workspaces.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AccountServiceFilterTest {

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	@Test
	void theServiceFilterAloneIsolatesWorkspacesWithRlsBypassed() {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		AccountService service = new AccountService(owner.client());
		Fixture a = fixtures.workspaceWithOwner();
		Fixture b = fixtures.workspaceWithOwner();
		UUID accountA = fixtures.insertAccount(a.workspaceId(), "Conta A", "CHECKING", 100, false);
		UUID accountB = fixtures.insertAccount(b.workspaceId(), "Conta B", "CHECKING", 200, false);
		fixtures.insertAccount(b.workspaceId(), "Arquivada B", "CHECKING", 300, true);

		assertThat(service.get(a.workspaceId(), accountA).balanceCents()).isEqualTo(100);
		assertThat(service.get(b.workspaceId(), accountB).balanceCents()).isEqualTo(200);
		assertThatThrownBy(() -> service.get(a.workspaceId(), accountB)).isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> service.get(b.workspaceId(), accountA)).isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> service.get(a.workspaceId(), UUID.randomUUID()))
				.isInstanceOf(AccountNotFoundException.class);

		assertThat(service.list(a.workspaceId(), true)).extracting(AccountResponse::name).containsExactly("Conta A");
		assertThat(service.list(b.workspaceId(), true)).extracting(AccountResponse::name)
				.containsExactly("Arquivada B", "Conta B");
	}

}
