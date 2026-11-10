package com.tostao.tostao_api.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.common.error.TransactionNotFoundException;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;
import com.tostao.tostao_api.transaction.dto.TransactionPageResponse;
import com.tostao.tostao_api.transaction.dto.UpdateTransactionRequest;
import com.tostao.tostao_api.workspace.TenantSession;

/**
 * As duas camadas de isolamento da mudança de estado, uma de cada vez (SC-009). Camada de dados: o
 * {@code UPDATE} do estado roda como {@code tostao_app} <b>sem filtro de workspace</b>, e só o RLS barra.
 * Camada de serviço: o serviço roda com a conexão do dono (RLS neutralizado) e o filtro explícito por
 * {@code workspace_id} isola sozinho.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class PendingLayersTest {

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

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private static void actAs(UUID userId) {
		Jwt jwt = Jwt.withTokenValue("teste").header("alg", "none").subject(userId.toString()).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}

	private UUID pending(WorkspaceFixtures f, PendingScenario s, String description) {
		return f.insertLedgerTransaction(s.owner().workspaceId(), s.checking(), s.owner().userId(), s.food(), "EXPENSE",
				1_000, LocalDate.of(2026, 11, 20), description, false, "PENDING");
	}

	private int updateAllAsApp(Fixture who, UUID workspace) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(who.userId(), workspace);
			// sem filtro de workspace de propósito: só o RLS protege
			return appJdbc.sql("update transactions set status = 'CLEARED', updated_at = now() where status = 'PENDING'")
					.update();
		});
	}

	@Test
	void theDatabaseLayerAloneOnlyReachesTheOwnWorkspace() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		PendingScenario a = PendingScenario.create(f);
		PendingScenario b = PendingScenario.create(f);
		pending(f, a, "A1");
		pending(f, a, "A2");
		UUID foreign = pending(f, b, "B1");

		int updated = updateAllAsApp(a.owner(), a.owner().workspaceId());

		assertThat(updated).isEqualTo(2);
		assertThat(PendingScenario.statusOf(owner, foreign)).isEqualTo("PENDING");
	}

	@Test
	void aViewerReachesNothingAndNoContextReachesNothing() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		PendingScenario a = PendingScenario.create(f);
		UUID id = pending(f, a, "A1");
		Fixture viewer = f.addMember(a.owner().workspaceId(), "VIEWER");

		assertThat(updateAllAsApp(viewer, a.owner().workspaceId())).isZero();
		int noContext = new TransactionTemplate(transactionManager).execute(status -> appJdbc
				.sql("update transactions set status = 'CLEARED' where status = 'PENDING'").update());

		assertThat(noContext).isZero();
		assertThat(PendingScenario.statusOf(owner, id)).isEqualTo("PENDING");
	}

	@Test
	void theServiceFilterAloneIsolatesWorkspacesWithRlsBypassed() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		TransactionService service = new TransactionService(owner.client());
		PendingScenario a = PendingScenario.create(f);
		PendingScenario b = PendingScenario.create(f);
		UUID foreign = pending(f, b, "B1");
		pending(f, a, "A1");
		String bBefore = PendingScenario.transactionsFingerprint(owner, b.owner().workspaceId());
		actAs(a.owner().userId());

		// efetivar e editar o estado de um lançamento de B pelo workspace de A: não encontrado, e B intacto
		assertThatThrownBy(() -> service.clear(a.owner().workspaceId(), foreign)).isInstanceOf(TransactionNotFoundException.class);
		UpdateTransactionRequest edit = new UpdateTransactionRequest(null, a.checking(), null, 1_000L,
				LocalDate.of(2026, 11, 20), "x", null, a.food(), "CLEARED");
		assertThatThrownBy(() -> service.update(a.owner().workspaceId(), foreign, edit))
				.isInstanceOf(TransactionNotFoundException.class);

		// a listagem filtrada por estado de A não traz nada de B
		TransactionPageResponse listA = service.list(a.owner().workspaceId(),
				new TransactionFilter(1, 100, null, null, null, null, null, null, TransactionStatus.PENDING));
		assertThat(listA.items()).hasSize(1).noneMatch(i -> i.id().equals(foreign));
		assertThat(listA.totalItems()).isEqualTo(1);

		assertThat(PendingScenario.transactionsFingerprint(owner, b.owner().workspaceId())).isEqualTo(bBefore);
	}

}
