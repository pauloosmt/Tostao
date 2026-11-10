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
import com.tostao.tostao_api.common.error.AccountNotFoundException;
import com.tostao.tostao_api.common.error.CategoryNotFoundException;
import com.tostao.tostao_api.common.error.InstallmentPlanNotFoundException;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;
import com.tostao.tostao_api.transaction.dto.CreateInstallmentPurchaseRequest;
import com.tostao.tostao_api.workspace.TenantSession;

/**
 * As duas camadas de isolamento da compra parcelada, uma de cada vez (SC-009). Camada de dados: o SQL roda como
 * {@code tostao_app} <b>sem filtro de workspace</b>, e só o RLS barra. Camada de serviço: o serviço roda com a conexão
 * do dono (RLS neutralizado) e o filtro explícito por {@code workspace_id} isola sozinho.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InstallmentLayersTest {

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

	/** Uma parcela pendente de um plano novo no workspace do cenário. */
	private UUID installment(WorkspaceFixtures f, InstallmentScenario s, UUID group, int number) {
		return f.insertInstallment(s.owner().workspaceId(), s.checking(), s.owner().userId(), s.gadgets(), group, number, 3,
				1_000, LocalDate.of(2026, 12, 10), "Notebook (" + number + "/3)", "PENDING", false);
	}

	/** Exclui logicamente as parcelas pendentes, sem filtro de workspace de propósito: só o RLS protege. */
	private int excludeAllPendingAsApp(Fixture who, UUID workspace) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(who.userId(), workspace);
			return appJdbc.sql("""
					update transactions set deleted_at = now(), updated_at = now()
					where installment_group_id is not null and status = 'PENDING' and deleted_at is null
					""").update();
		});
	}

	@Test
	void theDatabaseLayerAloneOnlyReachesTheOwnWorkspace() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		InstallmentScenario a = InstallmentScenario.create(f);
		InstallmentScenario b = InstallmentScenario.create(f);
		UUID groupA = UUID.randomUUID();
		UUID groupB = UUID.randomUUID();
		installment(f, a, groupA, 1);
		installment(f, a, groupA, 2);
		UUID foreign = installment(f, b, groupB, 1);

		int excluded = excludeAllPendingAsApp(a.owner(), a.owner().workspaceId());

		assertThat(excluded).isEqualTo(2);
		assertThat(owner.client().sql("select deleted_at is null from transactions where id = :id").param("id", foreign)
				.query(Boolean.class).single()).as("a parcela do outro workspace segue viva").isTrue();
	}

	@Test
	void theDatabaseLayerAloneRefusesToInsertAnInstallmentInAnotherWorkspace() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		InstallmentScenario a = InstallmentScenario.create(f);
		InstallmentScenario b = InstallmentScenario.create(f);
		String before = PendingScenario.transactionsFingerprint(owner, b.owner().workspaceId());

		assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(a.owner().userId(), a.owner().workspaceId());
			// sem checar nada no serviço: o INSERT aponta o workspace B estando no contexto do A
			return appJdbc.sql("""
					insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
					                          description, created_by, installment_group_id, installment_number,
					                          installment_count)
					values (:w, :a, :c, 'EXPENSE', -1000, date '2026-11-10', 'Invasora', :u, gen_random_uuid(), 1, 3)""")
					.param("w", b.owner().workspaceId()).param("a", b.checking()).param("c", b.gadgets())
					.param("u", a.owner().userId()).update();
		})).rootCause().hasMessageContaining("row-level security");

		assertThat(PendingScenario.transactionsFingerprint(owner, b.owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void aViewerReachesNothingAndNoContextReachesNothing() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		InstallmentScenario a = InstallmentScenario.create(f);
		UUID id = installment(f, a, UUID.randomUUID(), 1);
		Fixture viewer = f.addMember(a.owner().workspaceId(), "VIEWER");

		assertThat(excludeAllPendingAsApp(viewer, a.owner().workspaceId())).isZero();
		int noContext = new TransactionTemplate(transactionManager).execute(status -> appJdbc
				.sql("update transactions set deleted_at = now() where installment_group_id is not null").update());

		assertThat(noContext).isZero();
		assertThat(owner.client().sql("select deleted_at is null from transactions where id = :id").param("id", id)
				.query(Boolean.class).single()).isTrue();
	}

	@Test
	void theServiceFilterAloneIsolatesWorkspacesWithRlsBypassed() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		InstallmentService service = new InstallmentService(owner.client());
		InstallmentScenario a = InstallmentScenario.create(f);
		InstallmentScenario b = InstallmentScenario.create(f);
		UUID groupB = UUID.randomUUID();
		installment(f, b, groupB, 1);
		installment(f, b, groupB, 2);
		String aBefore = PendingScenario.transactionsFingerprint(owner, a.owner().workspaceId());
		String bBefore = PendingScenario.transactionsFingerprint(owner, b.owner().workspaceId());
		actAs(a.owner().userId());

		// consultar e cancelar o plano de B pelo workspace de A: não encontrado, e B intacto
		assertThatThrownBy(() -> service.plan(a.owner().workspaceId(), groupB))
				.isInstanceOf(InstallmentPlanNotFoundException.class);
		assertThatThrownBy(() -> service.cancel(a.owner().workspaceId(), groupB))
				.isInstanceOf(InstallmentPlanNotFoundException.class);

		// registrar com a conta ou a categoria de B pelo workspace de A: não encontrado, e nada gravado
		assertThatThrownBy(() -> service.create(a.owner().workspaceId(), new CreateInstallmentPurchaseRequest(b.checking(),
				a.gadgets(), 30_000L, 3, LocalDate.of(2026, 11, 10), "x", null, null)))
				.isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> service.create(a.owner().workspaceId(), new CreateInstallmentPurchaseRequest(a.checking(),
				b.gadgets(), 30_000L, 3, LocalDate.of(2026, 11, 10), "x", null, null)))
				.isInstanceOf(CategoryNotFoundException.class);

		assertThat(PendingScenario.transactionsFingerprint(owner, b.owner().workspaceId())).isEqualTo(bBefore);
		assertThat(PendingScenario.transactionsFingerprint(owner, a.owner().workspaceId())).isEqualTo(aBefore);
		// e o mesmo serviço, no workspace certo, enxerga o plano
		assertThat(service.plan(b.owner().workspaceId(), groupB).installments()).hasSize(2);
	}

}
