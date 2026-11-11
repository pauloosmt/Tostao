package com.tostao.tostao_api.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
import com.tostao.tostao_api.common.error.RecurrenceNotFoundException;
import com.tostao.tostao_api.recurrence.dto.CreateRecurrenceRequest;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;
import com.tostao.tostao_api.transaction.TransactionType;
import com.tostao.tostao_api.workspace.TenantSession;

/**
 * As duas camadas de isolamento da transferência recorrente, uma de cada vez (FR-013, SC-008). Camada de dados: o SQL roda como
 * {@code tostao_app} <b>sem filtro de workspace</b>, e só o RLS e as chaves compostas barram. Camada de serviço: o serviço roda com
 * a conexão do dono (RLS neutralizado) e o filtro explícito por {@code workspace_id} isola sozinho.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransferRecurrenceLayersTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

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

	/** O banco é compartilhado: deixa de lado as recorrências que sobraram de outros testes. */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	private static void actAs(UUID userId) {
		Jwt jwt = Jwt.withTokenValue("teste").header("alg", "none").subject(userId.toString()).build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
	}

	private RecurrenceScenario scenario() {
		return RecurrenceScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	/** Uma transferência recorrente com as ocorrências já geradas, pela conexão do dono. */
	private UUID transfer(RecurrenceScenario s) {
		UUID id = owner.client().sql("""
				insert into recurrences (workspace_id, account_id, destination_account_id, type, amount_cents, description, frequency, start_on, created_by)
				values (:w, :a, :d, 'TRANSFER', 50000, 'Reserva', 'MONTHLY', :s, :u) returning id""")
				.param("w", s.owner().workspaceId()).param("a", s.checking()).param("d", s.savings()).param("s", TODAY)
				.param("u", s.owner().userId()).query(UUID.class).single();
		owner.client().sql("select generate_recurrence_occurrences(cast(:d as date), cast(:r as uuid))").param("d", TODAY).param("r", id)
				.query(Integer.class).single();
		return id;
	}

	private <T> T asApp(UUID user, UUID workspace, Function<JdbcClient, T> work) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(user, workspace);
			return work.apply(appJdbc);
		});
	}

	private long live(UUID recurrence) {
		return owner.client().sql("select count(*) from transactions where recurrence_id = :r and deleted_at is null").param("r", recurrence)
				.query(Long.class).single();
	}

	// ---- camada de dados --------------------------------------------------------------------------------------

	@Test
	void theDatabaseLayerAloneOnlyReachesTheOwnWorkspace() {
		RecurrenceScenario a = scenario();
		RecurrenceScenario b = scenario();
		UUID ra = transfer(a);
		UUID rb = transfer(b);

		// sem filtro de workspace de propósito: só o RLS protege
		int ended = asApp(a.owner().userId(), a.owner().workspaceId(),
				db -> db.sql("update recurrences set ended_at = now(), updated_at = now() where ended_at is null").update());
		int deleted = asApp(a.owner().userId(), a.owner().workspaceId(),
				db -> db.sql("update transactions set deleted_at = now() where recurrence_id is not null and deleted_at is null").update());

		assertThat(ended).isEqualTo(1);
		assertThat(deleted).as("as 26 linhas da transferência de A").isEqualTo(26);
		assertThat(owner.client().sql("select ended_at is null from recurrences where id = :id").param("id", rb).query(Boolean.class).single())
				.as("a recorrência do outro workspace segue ativa").isTrue();
		assertThat(live(rb)).as("as pontas do outro workspace seguem vivas").isEqualTo(26);
		assertThat(live(ra)).isZero();
	}

	@Test
	void theDatabaseLayerAloneRefusesATransferRecurrenceInOrWithAnotherWorkspace() {
		RecurrenceScenario a = scenario();
		RecurrenceScenario b = scenario();

		// o workspace de B: barrado pelo RLS
		assertThatThrownBy(() -> asApp(a.owner().userId(), a.owner().workspaceId(), db -> db.sql("""
				insert into recurrences (workspace_id, account_id, destination_account_id, type, amount_cents, description, frequency, start_on, created_by)
				values (:w, :a, :d, 'TRANSFER', 100, 'Invasora', 'MONTHLY', date '2026-11-10', :u)""")
				.param("w", b.owner().workspaceId()).param("a", b.checking()).param("d", b.savings()).param("u", a.owner().userId()).update()))
				.rootCause().hasMessageContaining("row-level security");
		// o workspace de A com o destino de B: barrado pela chave composta (destino e workspace)
		assertThatThrownBy(() -> asApp(a.owner().userId(), a.owner().workspaceId(), db -> db.sql("""
				insert into recurrences (workspace_id, account_id, destination_account_id, type, amount_cents, description, frequency, start_on, created_by)
				values (:w, :a, :d, 'TRANSFER', 100, 'Invasora', 'MONTHLY', date '2026-11-10', :u)""")
				.param("w", a.owner().workspaceId()).param("a", a.checking()).param("d", b.savings()).param("u", a.owner().userId()).update()))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(owner.client().sql("select count(*) from recurrences where description = 'Invasora'").query(Long.class).single()).isZero();
	}

	@Test
	void aViewerAndNoContextReachNothing() {
		RecurrenceScenario a = scenario();
		UUID r = transfer(a);
		Fixture viewer = new WorkspaceFixtures(owner, tokens).addMember(a.owner().workspaceId(), "VIEWER");

		int endedByViewer = asApp(viewer.userId(), a.owner().workspaceId(),
				db -> db.sql("update recurrences set ended_at = now() where id = :id").param("id", r).update());
		int deletedByViewer = asApp(viewer.userId(), a.owner().workspaceId(),
				db -> db.sql("update transactions set deleted_at = now() where recurrence_id = :id").param("id", r).update());
		int noContext = new TransactionTemplate(transactionManager).execute(status -> appJdbc
				.sql("update recurrences set ended_at = now() where id = :id").param("id", r).update());

		assertThat(endedByViewer).isZero();
		assertThat(deletedByViewer).isZero();
		assertThat(noContext).isZero();
		assertThat(live(r)).isEqualTo(26);
	}

	// ---- camada de serviço ------------------------------------------------------------------------------------

	@Test
	void theServiceFilterAloneIsolatesWorkspacesWithRlsBypassed() {
		RecurrenceService service = new RecurrenceService(owner.client());
		RecurrenceScenario a = scenario();
		RecurrenceScenario b = scenario();
		UUID rb = transfer(b);
		transfer(a);
		String aBefore = RecurrenceScenario.recurrencesFingerprint(owner, a.owner().workspaceId());
		String bBefore = RecurrenceScenario.recurrencesFingerprint(owner, b.owner().workspaceId());
		actAs(a.owner().userId());

		// consultar e encerrar a transferência de B pelo workspace de A: não encontrada, e B intacta
		assertThatThrownBy(() -> service.get(a.owner().workspaceId(), rb)).isInstanceOf(RecurrenceNotFoundException.class);
		assertThatThrownBy(() -> service.end(a.owner().workspaceId(), rb)).isInstanceOf(RecurrenceNotFoundException.class);
		assertThat(service.list(a.owner().workspaceId())).hasSize(1).noneMatch(r -> r.id().equals(rb));

		// criar com a origem ou o destino de B pelo workspace de A: não encontrado, e nada gravado
		LocalDate start = RecurrenceScenario.today();
		assertThatThrownBy(() -> service.create(a.owner().workspaceId(), new CreateRecurrenceRequest(a.checking(), b.savings(), null,
				TransactionType.TRANSFER, 100L, "x", null, RecurrenceFrequency.MONTHLY, start, null, null))).isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> service.create(a.owner().workspaceId(), new CreateRecurrenceRequest(b.checking(), a.savings(), null,
				TransactionType.TRANSFER, 100L, "x", null, RecurrenceFrequency.MONTHLY, start, null, null))).isInstanceOf(AccountNotFoundException.class);

		assertThat(RecurrenceScenario.recurrencesFingerprint(owner, b.owner().workspaceId())).isEqualTo(bBefore);
		assertThat(RecurrenceScenario.recurrencesFingerprint(owner, a.owner().workspaceId())).isEqualTo(aBefore);
		// e o mesmo serviço, no workspace certo, enxerga a transferência
		actAs(b.owner().userId());
		assertThat(service.get(b.owner().workspaceId(), rb).occurrences()).hasSize(26);
	}

	@Test
	void endingTouchesOnlyTheLegsOfThatRecurrenceInThatWorkspace() {
		RecurrenceService service = new RecurrenceService(owner.client());
		RecurrenceScenario a = scenario();
		RecurrenceScenario b = scenario();
		UUID ra = transfer(a);
		UUID rb = transfer(b);
		actAs(a.owner().userId());

		var result = service.end(a.owner().workspaceId(), ra);

		assertThat(result.cancelled()).isEqualTo(12);
		assertThat(live(ra)).isEqualTo(2);
		assertThat(live(rb)).isEqualTo(26);
	}

}
