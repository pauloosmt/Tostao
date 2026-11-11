package com.tostao.tostao_api.recurrence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.automation.AutomationService;
import com.tostao.tostao_api.automation.AutomationService.RunResult;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * História 2: a transferência acontece sozinha no dia (FR-006, FR-007, FR-009, SC-002, SC-003). A execução automática gera os
 * pares e efetiva as duas pontas juntas; repetir ou simultanear nunca duplica nem parte um par; o que foi excluído não volta.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransferOccurrencesTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	AutomationService automation;

	/** A execução age sobre o banco todo: deixa de lado o que sobrou de outros testes. */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
		owner.client().sql("select auto_clear_due(date '2100-12-31')").query(Integer.class).single();
	}

	private record World(Fixture fx, UUID checking, UUID savings, UUID expense) {
	}

	private World world() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		Fixture fx = f.workspaceWithOwner();
		return new World(fx, f.insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 500_000, false),
				f.insertAccount(fx.workspaceId(), "Poupança", "SAVINGS", 0, false),
				f.insertCategory(fx.workspaceId(), null, "Aluguel", "EXPENSE", false));
	}

	private UUID transfer(World w, String frequency, LocalDate start, boolean autoClear) {
		return owner.client().sql("""
				insert into recurrences (workspace_id, account_id, destination_account_id, type, amount_cents, description, frequency,
				                         start_on, auto_clear, created_by)
				values (:w, :a, :d, 'TRANSFER', 50000, 'Reserva', :f, :s, :ac, :u) returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.checking()).param("d", w.savings()).param("f", frequency)
				.param("s", start).param("ac", autoClear).param("u", w.fx().userId()).query(UUID.class).single();
	}

	private long count(UUID recurrence, String condition) {
		return owner.client().sql("select count(*) from transactions where recurrence_id = :r " + condition).param("r", recurrence)
				.query(Long.class).single();
	}

	private long cleared(UUID account) {
		return owner.client().sql("select coalesce(sum(amount_cents), 0) from transactions where account_id = :a and status = 'CLEARED' and deleted_at is null")
				.param("a", account).query(Long.class).single();
	}

	/** Todo número tem exatamente uma saída e uma entrada, no mesmo grupo. */
	private void assertEveryOccurrenceIsAWholePair(UUID recurrence) {
		List<Long> bad = owner.client().sql("""
				select count(*) from (
				  select occurrence_number from transactions where recurrence_id = :r
				  group by occurrence_number
				  having count(*) <> 2 or count(*) filter (where amount_cents < 0) <> 1 or count(*) filter (where amount_cents > 0) <> 1
				      or count(distinct transfer_group_id) <> 1
				) x""").param("r", recurrence).query(Long.class).list();
		assertThat(bad).containsExactly(0L);
	}

	// ---- gerar e efetivar -------------------------------------------------------------------------------------

	@Test
	void aRunGeneratesThePairsAndClearsBothLegsOfWhatFellDue() {
		World w = world();
		// semanal, começando há 20 dias: as ocorrências 0, 1 e 2 (há 20, 13 e 6 dias) já venceram
		UUID recurrence = transfer(w, "WEEKLY", TODAY.minusDays(20), true);
		int occurrences = RecurrenceCalendar.expected(TODAY.minusDays(20), "WEEKLY", null, TODAY).size();

		RunResult result = automation.runOnce(TODAY);

		assertThat(result.generated()).isEqualTo(2 * occurrences);
		assertThat(result.cleared()).isEqualTo(6);
		assertThat(count(recurrence, "and status = 'CLEARED' and not auto_clear")).isEqualTo(6);
		assertThat(count(recurrence, "and status = 'CLEARED' and occurred_on <= '" + TODAY + "'")).isEqualTo(6);
		assertThat(count(recurrence, "and status = 'PENDING' and auto_clear and occurred_on > '" + TODAY + "'")).isEqualTo(2 * (occurrences - 3));
		assertEveryOccurrenceIsAWholePair(recurrence);
		// cada conta muda em exatos centavos e a soma dos saldos não muda
		assertThat(cleared(w.checking())).isEqualTo(-150_000L);
		assertThat(cleared(w.savings())).isEqualTo(150_000L);
		assertThat(cleared(w.checking()) + cleared(w.savings())).isZero();
	}

	@Test
	void withoutTheMarkerTheDateNeverClears() {
		World w = world();
		UUID recurrence = transfer(w, "WEEKLY", TODAY.minusDays(20), false);

		RunResult result = automation.runOnce(TODAY);

		assertThat(result.generated()).isPositive();
		assertThat(result.cleared()).isZero();
		assertThat(count(recurrence, "and status = 'CLEARED'")).isZero();
		assertThat(cleared(w.checking())).isZero();
		assertEveryOccurrenceIsAWholePair(recurrence);
	}

	@Test
	void aLaterRunClearsWhatFellDueInTheMeantimeAndGeneratesTheNewHorizon() {
		World w = world();
		UUID recurrence = transfer(w, "MONTHLY", TODAY, true);
		automation.runOnce(TODAY);
		long before = count(recurrence, "");

		RunResult later = automation.runOnce(TODAY.plusMonths(2));

		assertThat(count(recurrence, "")).isEqualTo(before + 4);
		assertThat(later.generated()).isEqualTo(4);
		// as ocorrências de hoje e de daqui a um e dois meses (números 0, 1 e 2), seis pontas
		assertThat(later.cleared()).isEqualTo(4);
		assertEveryOccurrenceIsAWholePair(recurrence);
	}

	// ---- idempotência -----------------------------------------------------------------------------------------

	@Test
	void repeatingTheRunDoesNothingAndLeavesEveryPairWhole() {
		World w = world();
		UUID recurrence = transfer(w, "WEEKLY", TODAY.minusDays(20), true);

		RunResult first = automation.runOnce(TODAY);
		RunResult second = automation.runOnce(TODAY);
		RunResult third = automation.runOnce(TODAY);

		assertThat(first.generated()).isPositive();
		assertThat(second).isEqualTo(new RunResult(false, 0, 0));
		assertThat(third).isEqualTo(new RunResult(false, 0, 0));
		assertEveryOccurrenceIsAWholePair(recurrence);
	}

	@Test
	void simultaneousRunsCreateEachPairOnceAndNeverSplitOne() throws Exception {
		World w = world();
		UUID recurrence = transfer(w, "WEEKLY", TODAY.minusDays(20), true);
		int rows = 2 * RecurrenceCalendar.expected(TODAY.minusDays(20), "WEEKLY", null, TODAY).size();
		ExecutorService pool = Executors.newFixedThreadPool(6);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<RunResult>> results = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				results.add(pool.submit(() -> {
					go.await();
					return automation.runOnce(TODAY);
				}));
			}
			go.countDown();
			int generated = 0;
			int cleared = 0;
			for (Future<RunResult> result : results) {
				RunResult r = result.get(60, TimeUnit.SECONDS);
				generated += r.generated();
				cleared += r.cleared();
			}

			assertThat(generated).as("cada linha foi gerada por exatamente uma execução").isEqualTo(rows);
			assertThat(cleared).as("e efetivada uma só vez").isEqualTo(6);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(count(recurrence, "")).isEqualTo(rows);
		assertEveryOccurrenceIsAWholePair(recurrence);
		assertThat(cleared(w.checking())).isEqualTo(-150_000L);
	}

	@Test
	void aDeletedOccurrenceIsNeverRecreatedByALaterRun() {
		World w = world();
		UUID recurrence = transfer(w, "MONTHLY", TODAY, false);
		automation.runOnce(TODAY);
		owner.client().sql("update transactions set deleted_at = now() where recurrence_id = :r and occurrence_number = 4").param("r", recurrence).update();

		RunResult again = automation.runOnce(TODAY);

		assertThat(again.generated()).isZero();
		assertThat(count(recurrence, "and occurrence_number = 4 and deleted_at is not null")).isEqualTo(2);
		assertThat(count(recurrence, "and occurrence_number = 4 and deleted_at is null")).isZero();
	}

	// ---- quando não gera --------------------------------------------------------------------------------------

	@Test
	void anArchivedAccountPausesOnlyThatRecurrenceAndRestoringResumesIt() {
		World w = world();
		UUID transfer = transfer(w, "MONTHLY", TODAY, false);
		UUID expense = owner.client().sql("""
				insert into recurrences (workspace_id, account_id, category_id, type, amount_cents, description, frequency, start_on, created_by)
				values (:w, :a, :c, 'EXPENSE', 10000, 'Aluguel', 'MONTHLY', :s, :u) returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.checking()).param("c", w.expense()).param("s", TODAY).param("u", w.fx().userId())
				.query(UUID.class).single();
		owner.client().sql("update accounts set archived = true where id = :id").param("id", w.savings()).update();

		automation.runOnce(TODAY);

		assertThat(count(transfer, "")).isZero();
		assertThat(count(expense, "")).isEqualTo(13);
		owner.client().sql("update accounts set archived = false where id = :id").param("id", w.savings()).update();
		automation.runOnce(TODAY);
		assertThat(count(transfer, "")).isEqualTo(26);
		assertEveryOccurrenceIsAWholePair(transfer);
	}

	@Test
	void twoWorkspacesNeverMix() {
		World a = world();
		World b = world();
		UUID ra = transfer(a, "WEEKLY", TODAY.minusDays(20), true);
		UUID rb = transfer(b, "WEEKLY", TODAY.minusDays(20), true);

		automation.runOnce(TODAY);

		for (World world : new World[] { a, b }) {
			UUID recurrence = world == a ? ra : rb;
			assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = :r and workspace_id <> :w")
					.param("r", recurrence).param("w", world.fx().workspaceId()).query(Long.class).single()).isZero();
			assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = :r and account_id not in (:a, :b)")
					.param("r", recurrence).param("a", world.checking()).param("b", world.savings()).query(Long.class).single()).isZero();
			assertThat(cleared(world.checking())).isEqualTo(-150_000L);
			assertThat(cleared(world.savings())).isEqualTo(150_000L);
		}
	}

}
