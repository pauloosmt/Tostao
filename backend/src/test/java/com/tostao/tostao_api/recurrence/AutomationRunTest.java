package com.tostao.tostao_api.recurrence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.automation.AutomationService;
import com.tostao.tostao_api.automation.AutomationService.RunResult;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** A execução automática pelo serviço: gerar e efetivar na mesma execução, idempotência, a data dada e a trava. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AutomationRunTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

	@Autowired
	AutomationService automation;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	PostgreSQLContainer postgres;

	/** O banco é compartilhado entre os testes: deixa de lado o que sobrou de outros, para as contagens serem só daqui. */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
		owner.client().sql("select auto_clear_due(date '2100-12-31')").query(Integer.class).single();
	}

	private record World(Fixture fx, UUID account, UUID expense) {
	}

	private World world() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		Fixture fx = f.workspaceWithOwner();
		return new World(fx, f.insertAccount(fx.workspaceId(), "Conta", "CHECKING", 0, false),
				f.insertCategory(fx.workspaceId(), null, "Aluguel", "EXPENSE", false));
	}

	private UUID rec(World w, LocalDate start, boolean autoClear) {
		return owner.client().sql("""
				insert into recurrences (workspace_id, account_id, category_id, type, amount_cents, description,
				                         frequency, start_on, auto_clear, created_by)
				values (:w, :a, :c, 'EXPENSE', 150000, 'Aluguel', 'MONTHLY', :s, :ac, :u) returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", w.expense()).param("s", start)
				.param("ac", autoClear).param("u", w.fx().userId()).query(UUID.class).single();
	}

	private UUID marked(World w, LocalDate date) {
		return owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, created_by, status, auto_clear)
				values (:w, :a, :c, 'EXPENSE', -1000, :d, 'Marcado', :u, 'PENDING', true) returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", w.expense()).param("d", date)
				.param("u", w.fx().userId()).query(UUID.class).single();
	}

	private long count(UUID recurrence, String where) {
		return owner.client().sql("select count(*) from transactions where recurrence_id = :r " + where)
				.param("r", recurrence).query(Long.class).single();
	}

	private long countUpTo(UUID recurrence, LocalDate date) {
		return owner.client().sql("select count(*) from transactions where recurrence_id = :r and occurred_on <= :d")
				.param("r", recurrence).param("d", date).query(Long.class).single();
	}

	private String statusOf(UUID id) {
		return owner.client().sql("select status from transactions where id = :id").param("id", id).query(String.class)
				.single();
	}

	@Test
	void aRunGeneratesAndThenClearsWhatHasAlreadyFallenDueInTheSameRun() {
		World w = world();
		UUID r = rec(w, TODAY.minusDays(40), true);
		UUID due = marked(w, TODAY.minusDays(3));

		RunResult result = automation.runOnce(TODAY);

		assertThat(result.skipped()).isFalse();
		long total = count(r, "");
		long past = countUpTo(r, TODAY);
		assertThat(total).isGreaterThan(past).isGreaterThanOrEqualTo(13);
		assertThat(past).as("as vencidas desde o início").isGreaterThanOrEqualTo(2);
		assertThat(result.generated()).isEqualTo((int) total);
		// as geradas que já venceram, mais a marcada que sobrou, foram efetivadas na mesma execução
		assertThat(result.cleared()).isEqualTo((int) past + 1);
		assertThat(count(r, "and status = 'CLEARED' and not auto_clear")).isEqualTo(past);
		assertThat(count(r, "and status = 'PENDING' and auto_clear")).isEqualTo(total - past);
		assertThat(statusOf(due)).isEqualTo("CLEARED");
	}

	@Test
	void repeatingTheRunDoesNothing() {
		World w = world();
		rec(w, TODAY, true);
		marked(w, TODAY);

		RunResult first = automation.runOnce(TODAY);
		RunResult second = automation.runOnce(TODAY);

		assertThat(first.generated()).isEqualTo(13);
		assertThat(first.cleared()).isEqualTo(2);
		assertThat(second).isEqualTo(new RunResult(false, 0, 0));
	}

	@Test
	void theGivenDateDecidesWhatIsGeneratedAndWhatIsCleared() {
		World w = world();
		UUID r = rec(w, TODAY, true);

		automation.runOnce(TODAY);
		assertThat(count(r, "")).isEqualTo(13L);
		assertThat(count(r, "and status = 'CLEARED'")).isEqualTo(1L);

		// seis meses depois: o horizonte anda e as ocorrências que venceram no caminho são efetivadas
		RunResult later = automation.runOnce(TODAY.plusMonths(6));
		assertThat(later.generated()).isEqualTo(6);
		assertThat(count(r, "")).isEqualTo(19L);
		assertThat(later.cleared()).isEqualTo(6);
		assertThat(count(r, "and status = 'CLEARED'")).isEqualTo(7L);

		// uma data no passado não apaga nem desfaz nada
		RunResult earlier = automation.runOnce(TODAY.minusYears(1));
		assertThat(earlier).isEqualTo(new RunResult(false, 0, 0));
		assertThat(count(r, "")).isEqualTo(19L);
	}

	@Test
	void anEndedRecurrenceIsNeverGeneratedAgain() {
		World w = world();
		UUID r = rec(w, TODAY, false);
		owner.client().sql("update recurrences set ended_at = now() where id = :r").param("r", r).update();

		assertThat(automation.runOnce(TODAY.plusYears(3)).generated()).isZero();
		assertThat(count(r, "")).isZero();
	}

	@Test
	void whileAnotherRunHoldsTheAdvisoryLockTheRunIsSkippedAndNothingChanges() throws Exception {
		World w = world();
		UUID r = rec(w, TODAY, true);
		UUID due = marked(w, TODAY);

		try (Connection holder = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
				postgres.getPassword())) {
			holder.setAutoCommit(false);
			try (PreparedStatement lock = holder.prepareStatement("select pg_advisory_xact_lock(?)")) {
				lock.setLong(1, AutomationService.LOCK_KEY);
				lock.execute();
			}

			assertThat(automation.runOnce(TODAY)).isEqualTo(new RunResult(true, 0, 0));
			assertThat(count(r, "")).isZero();
			assertThat(statusOf(due)).isEqualTo("PENDING");
			holder.rollback();
		}

		RunResult afterRelease = automation.runOnce(TODAY);
		assertThat(afterRelease.skipped()).isFalse();
		assertThat(afterRelease.generated()).isEqualTo(13);
	}

	@Test
	void theFunctionsGiveTheSameResultWhenCalledAtTheSameTimeWithoutTheLock() throws Exception {
		World w = world();
		UUID r = rec(w, TODAY, true);
		UUID due = marked(w, TODAY);
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch ready = new CountDownLatch(threads);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<int[]>> futures = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			Callable<int[]> call = () -> {
				ready.countDown();
				go.await();
				int generated = owner.client().sql("select generate_recurrence_occurrences(cast(:d as date), null)")
						.param("d", TODAY).query(Integer.class).single();
				int cleared = owner.client().sql("select auto_clear_due(cast(:d as date))").param("d", TODAY)
						.query(Integer.class).single();
				return new int[] { generated, cleared };
			};
			futures.add(pool.submit(call));
		}
		ready.await(30, TimeUnit.SECONDS);
		go.countDown();
		int generated = 0;
		int cleared = 0;
		for (Future<int[]> future : futures) {
			int[] result = future.get(60, TimeUnit.SECONDS);
			generated += result[0];
			cleared += result[1];
		}
		pool.shutdown();

		// cada ocorrência foi gerada uma só vez e cada vencida efetivada uma só vez, qualquer que tenha sido a ordem
		assertThat(generated).isEqualTo(13);
		assertThat(count(r, "")).isEqualTo(13L);
		assertThat(owner.client().sql("select count(*) from (select 1 from transactions where recurrence_id = :r "
				+ "group by occurrence_number having count(*) > 1) x").param("r", r).query(Long.class).single()).isZero();
		assertThat(cleared).isEqualTo(2);
		assertThat(statusOf(due)).isEqualTo("CLEARED");
	}

}
