package com.tostao.tostao_api.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import com.tostao.tostao_api.automation.AutomationService;
import com.tostao.tostao_api.common.error.AccountNotFoundException;
import com.tostao.tostao_api.common.error.CategoryNotFoundException;
import com.tostao.tostao_api.common.error.RecurrenceNotFoundException;
import com.tostao.tostao_api.recurrence.dto.CreateRecurrenceRequest;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;
import com.tostao.tostao_api.transaction.TransactionType;
import com.tostao.tostao_api.workspace.TenantSession;

/**
 * As duas camadas de isolamento da recorrência, uma de cada vez (SC-010), e a execução automática com dados em dois
 * workspaces (SC-008). Camada de dados: o SQL roda como {@code tostao_app} <b>sem filtro de workspace</b>, e só o RLS
 * barra. Camada de serviço: o serviço roda com a conexão do dono (RLS neutralizado) e o filtro explícito por
 * {@code workspace_id} isola sozinho.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class RecurrenceLayersTest {

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
	@Autowired
	AutomationService automation;

	/** O banco é compartilhado: deixa de lado os lançamentos marcados e as recorrências que sobraram de outros testes. */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
		owner.client().sql("select auto_clear_due(date '2100-12-31')").query(Integer.class).single();
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

	private UUID recurrence(RecurrenceScenario s, boolean autoClear, LocalDate start) {
		return owner.client().sql("""
				insert into recurrences (workspace_id, account_id, category_id, type, amount_cents, description,
				                         frequency, start_on, auto_clear, created_by)
				values (:w, :a, :c, 'EXPENSE', 150000, 'Aluguel', 'MONTHLY', :s, :ac, :u) returning id""")
				.param("w", s.owner().workspaceId()).param("a", s.checking()).param("c", s.rent()).param("s", start)
				.param("ac", autoClear).param("u", s.owner().userId()).query(UUID.class).single();
	}

	private UUID occurrence(RecurrenceScenario s, UUID recurrence, int number) {
		return owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, status, recurrence_id, occurrence_number)
				values (:w, :a, :c, 'EXPENSE', -150000, date '2026-12-10', 'Aluguel', :u, 'PENDING', :r, :n) returning id""")
				.param("w", s.owner().workspaceId()).param("a", s.checking()).param("c", s.rent())
				.param("u", s.owner().userId()).param("r", recurrence).param("n", number).query(UUID.class).single();
	}

	private <T> T asApp(UUID user, UUID workspace, java.util.function.Function<JdbcClient, T> work) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			tenant.enterWorkspace(user, workspace);
			return work.apply(appJdbc);
		});
	}

	// ---- camada de dados --------------------------------------------------------------------------------------

	@Test
	void theDatabaseLayerAloneOnlyReachesTheOwnWorkspace() {
		RecurrenceScenario a = scenario();
		RecurrenceScenario b = scenario();
		UUID ra1 = recurrence(a, false, TODAY);
		UUID ra2 = recurrence(a, false, TODAY);
		UUID rb = recurrence(b, false, TODAY);
		UUID occB = occurrence(b, rb, 0);
		owner.client().sql("update transactions set auto_clear = true where id = :id").param("id", occB).update();
		UUID occA = occurrence(a, ra1, 0);
		owner.client().sql("update transactions set auto_clear = true where id = :id").param("id", occA).update();

		// sem filtro de workspace de propósito: só o RLS protege
		int ended = asApp(a.owner().userId(), a.owner().workspaceId(),
				db -> db.sql("update recurrences set ended_at = now(), updated_at = now() where ended_at is null").update());
		int unmarked = asApp(a.owner().userId(), a.owner().workspaceId(),
				db -> db.sql("update transactions set auto_clear = false where auto_clear").update());

		assertThat(ended).isEqualTo(2);
		assertThat(unmarked).isEqualTo(1);
		assertThat(owner.client().sql("select ended_at is null from recurrences where id = :id").param("id", rb)
				.query(Boolean.class).single()).as("a recorrência do outro workspace segue ativa").isTrue();
		assertThat(owner.client().sql("select auto_clear from transactions where id = :id").param("id", occB)
				.query(Boolean.class).single()).as("a ocorrência do outro workspace segue marcada").isTrue();
		assertThat(ra2).isNotNull();
	}

	@Test
	void theDatabaseLayerAloneRefusesToInsertInAnotherWorkspace() {
		RecurrenceScenario a = scenario();
		RecurrenceScenario b = scenario();
		UUID rb = recurrence(b, false, TODAY);

		assertThatThrownBy(() -> asApp(a.owner().userId(), a.owner().workspaceId(), db -> db.sql("""
				insert into recurrences (workspace_id, account_id, category_id, type, amount_cents, description,
				                         frequency, start_on, created_by)
				values (:w, :a, :c, 'EXPENSE', 100, 'Invasora', 'MONTHLY', date '2026-11-10', :u)""")
				.param("w", b.owner().workspaceId()).param("a", b.checking()).param("c", b.rent())
				.param("u", a.owner().userId()).update())).rootCause().hasMessageContaining("row-level security");
		assertThatThrownBy(() -> asApp(a.owner().userId(), a.owner().workspaceId(), db -> db.sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, status, recurrence_id, occurrence_number)
				values (:w, :a, :c, 'EXPENSE', -100, date '2026-11-10', 'Invasora', :u, 'PENDING', :r, 9)""")
				.param("w", b.owner().workspaceId()).param("a", b.checking()).param("c", b.rent())
				.param("u", a.owner().userId()).param("r", rb).update())).rootCause().hasMessageContaining("row-level security");
	}

	@Test
	void aViewerReachesNothingAndNoContextReachesNothing() {
		RecurrenceScenario a = scenario();
		UUID r = recurrence(a, false, TODAY);
		Fixture viewer = new WorkspaceFixtures(owner, tokens).addMember(a.owner().workspaceId(), "VIEWER");

		int ended = asApp(viewer.userId(), a.owner().workspaceId(),
				db -> db.sql("update recurrences set ended_at = now() where id = :id").param("id", r).update());
		int noContext = new TransactionTemplate(transactionManager).execute(status -> appJdbc
				.sql("update recurrences set ended_at = now() where id = :id").param("id", r).update());

		assertThat(ended).isZero();
		assertThat(noContext).isZero();
		assertThat(owner.client().sql("select ended_at is null from recurrences where id = :id").param("id", r)
				.query(Boolean.class).single()).isTrue();
	}

	// ---- camada de serviço ------------------------------------------------------------------------------------

	@Test
	void theServiceFilterAloneIsolatesWorkspacesWithRlsBypassed() {
		RecurrenceService service = new RecurrenceService(owner.client());
		RecurrenceScenario a = scenario();
		RecurrenceScenario b = scenario();
		UUID rb = recurrence(b, false, TODAY);
		occurrence(b, rb, 0);
		recurrence(a, false, TODAY);
		String aBefore = RecurrenceScenario.recurrencesFingerprint(owner, a.owner().workspaceId());
		String bBefore = RecurrenceScenario.recurrencesFingerprint(owner, b.owner().workspaceId());
		actAs(a.owner().userId());

		// consultar e encerrar a recorrência de B pelo workspace de A: não encontrada, e B intacta
		assertThatThrownBy(() -> service.get(a.owner().workspaceId(), rb)).isInstanceOf(RecurrenceNotFoundException.class);
		assertThatThrownBy(() -> service.end(a.owner().workspaceId(), rb)).isInstanceOf(RecurrenceNotFoundException.class);
		// a lista de A não traz nada de B
		assertThat(service.list(a.owner().workspaceId())).hasSize(1).noneMatch(r -> r.id().equals(rb));

		// criar com a conta ou a categoria de B pelo workspace de A: não encontrado, e nada gravado
		LocalDate start = RecurrenceScenario.today();
		assertThatThrownBy(() -> service.create(a.owner().workspaceId(), new CreateRecurrenceRequest(b.checking(), a.rent(),
				TransactionType.EXPENSE, 100L, "x", null, RecurrenceFrequency.MONTHLY, start, null, null)))
				.isInstanceOf(AccountNotFoundException.class);
		assertThatThrownBy(() -> service.create(a.owner().workspaceId(), new CreateRecurrenceRequest(a.checking(), b.rent(),
				TransactionType.EXPENSE, 100L, "x", null, RecurrenceFrequency.MONTHLY, start, null, null)))
				.isInstanceOf(CategoryNotFoundException.class);

		assertThat(RecurrenceScenario.recurrencesFingerprint(owner, b.owner().workspaceId())).isEqualTo(bBefore);
		assertThat(RecurrenceScenario.recurrencesFingerprint(owner, a.owner().workspaceId())).isEqualTo(aBefore);
		// e o mesmo serviço, no workspace certo, enxerga a recorrência
		actAs(b.owner().userId());
		assertThat(service.get(b.owner().workspaceId(), rb).occurrences()).hasSize(1);
	}

	// ---- a execução automática com dois workspaces ------------------------------------------------------------

	@Test
	void theAutomaticRunGeneratesAndClearsInEachWorkspaceOnlyWhatIsItsOwn() {
		RecurrenceScenario a = scenario();
		RecurrenceScenario b = scenario();
		UUID ra = recurrence(a, true, TODAY.minusDays(40));
		UUID rb = recurrence(b, false, TODAY);

		var result = automation.runOnce(TODAY);

		assertThat(result.generated()).isGreaterThan(0);
		for (RecurrenceScenario s : List.of(a, b)) {
			UUID r = s == a ? ra : rb;
			long wrong = owner.client().sql("""
					select count(*) from transactions t
					join accounts ac on ac.id = t.account_id
					join categories ca on ca.id = t.category_id
					where t.recurrence_id = :r
					  and (t.workspace_id <> :w or ac.workspace_id <> :w or ca.workspace_id <> :w or t.created_by <> :u)""")
					.param("r", r).param("w", s.owner().workspaceId()).param("u", s.owner().userId()).query(Long.class).single();
			assertThat(wrong).as("nenhuma linha de um workspace aponta para outro").isZero();
		}
		// nenhuma linha de A tem a conta ou a categoria de B, e vice-versa
		assertThat(owner.client().sql("select count(*) from transactions where workspace_id = :w and (account_id = :x or category_id = :y)")
				.param("w", a.owner().workspaceId()).param("x", b.checking()).param("y", b.rent()).query(Long.class).single()).isZero();
		assertThat(owner.client().sql("select count(*) from transactions where workspace_id = :w and (account_id = :x or category_id = :y)")
				.param("w", b.owner().workspaceId()).param("x", a.checking()).param("y", a.rent()).query(Long.class).single()).isZero();
		// o marcador de A efetivou só as vencidas de A; B, sem marcador, nada
		assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = :r and status = 'CLEARED'")
				.param("r", ra).query(Long.class).single()).isGreaterThanOrEqualTo(2);
		assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = :r and status = 'CLEARED'")
				.param("r", rb).query(Long.class).single()).isZero();
	}

}
