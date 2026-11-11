package com.tostao.tostao_api.recurrence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
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
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * A função de geração da {@code V15} direto no banco (SC-001, SC-002): o calendário contra o da despesa, o par de pontas, o
 * grupo determinístico, a idempotência (em série e em paralelo), o que não gera e o que continua igual para receita e despesa.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class TransferGenerationFunctionTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	/** As funções agem sobre o banco todo: deixa de lado o que sobrou de outros testes. */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
	}

	private record World(Fixture fx, UUID checking, UUID savings, UUID expense) {
	}

	private World world() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		Fixture fx = f.workspaceWithOwner();
		return new World(fx, f.insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false),
				f.insertAccount(fx.workspaceId(), "Poupança", "SAVINGS", 0, false),
				f.insertCategory(fx.workspaceId(), null, "Aluguel", "EXPENSE", false));
	}

	private UUID transfer(World w, String frequency, LocalDate start, LocalDate end, boolean autoClear) {
		return owner.client().sql("""
				insert into recurrences (workspace_id, account_id, destination_account_id, type, amount_cents, description, notes,
				                         frequency, start_on, end_on, auto_clear, created_by)
				values (:w, :a, :d, 'TRANSFER', 50000, 'Reserva', 'observação', :f, :s, :e, :ac, :u) returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.checking()).param("d", w.savings()).param("f", frequency)
				.param("s", start).param("e", end).param("ac", autoClear).param("u", w.fx().userId()).query(UUID.class).single();
	}

	private UUID expense(World w, String frequency, LocalDate start) {
		return owner.client().sql("""
				insert into recurrences (workspace_id, account_id, category_id, type, amount_cents, description, frequency, start_on, created_by)
				values (:w, :a, :c, 'EXPENSE', 50000, 'Aluguel', :f, :s, :u) returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.checking()).param("c", w.expense()).param("f", frequency)
				.param("s", start).param("u", w.fx().userId()).query(UUID.class).single();
	}

	private int generate(UUID recurrence) {
		return owner.client().sql("select generate_recurrence_occurrences(cast(:d as date), cast(:r as uuid))")
				.param("d", TODAY).param("r", recurrence).query(Integer.class).single();
	}

	private record Row(int number, LocalDate date, long cents, UUID account, UUID group, String status, boolean autoClear, String type,
			UUID category, String description, String notes, UUID createdBy, UUID workspace, boolean deleted) {
	}

	private List<Row> rows(UUID recurrence) {
		return owner.client().sql("""
				select occurrence_number, occurred_on, amount_cents, account_id, transfer_group_id, status, auto_clear, type, category_id,
				       description, notes, created_by, workspace_id, deleted_at is not null
				from transactions where recurrence_id = :r order by occurrence_number, amount_cents""")
				.param("r", recurrence)
				.query((rs, i) -> new Row(rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getLong(3), rs.getObject(4, UUID.class),
						rs.getObject(5, UUID.class), rs.getString(6), rs.getBoolean(7), rs.getString(8), rs.getObject(9, UUID.class),
						rs.getString(10), rs.getString(11), rs.getObject(12, UUID.class), rs.getObject(13, UUID.class), rs.getBoolean(14)))
				.list();
	}

	private static UUID expectedGroup(UUID recurrence, int number) throws Exception {
		byte[] md5 = MessageDigest.getInstance("MD5").digest((recurrence + ":" + number).getBytes(StandardCharsets.UTF_8));
		String hex = HexFormat.of().formatHex(md5);
		return UUID.fromString(hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-" + hex.substring(16, 20)
				+ "-" + hex.substring(20));
	}

	// ---- calendário e par -------------------------------------------------------------------------------------

	@Test
	void theCalendarIsTheSameAsTheCalendarOfAnExpenseForEveryFrequency() {
		World w = world();
		for (Object[] c : new Object[][] { { "WEEKLY", LocalDate.of(2026, 10, 1) }, { "MONTHLY", LocalDate.of(2026, 1, 31) },
				{ "MONTHLY", LocalDate.of(2026, 11, 30) }, { "YEARLY", LocalDate.of(2024, 2, 29) }, { "YEARLY", LocalDate.of(2026, 11, 5) } }) {
			String frequency = (String) c[0];
			LocalDate start = (LocalDate) c[1];
			UUID recurrence = transfer(w, frequency, start, null, false);
			List<LocalDate> expected = RecurrenceCalendar.expected(start, frequency, null, TODAY);

			int inserted = generate(recurrence);

			List<Row> rows = rows(recurrence);
			assertThat(inserted).as(frequency + " " + start).isEqualTo(2 * expected.size());
			assertThat(rows.stream().filter(r -> r.cents() < 0).map(Row::date).toList()).as(frequency + " " + start + " saídas")
					.containsExactlyElementsOf(expected);
			assertThat(rows.stream().filter(r -> r.cents() > 0).map(Row::date).toList()).as(frequency + " " + start + " entradas")
					.containsExactlyElementsOf(expected);
		}
	}

	@Test
	void eachOccurrenceIsAPairOfOppositeLegsOnTheRightAccountsWithOneGroup() throws Exception {
		World w = world();
		UUID recurrence = transfer(w, "MONTHLY", LocalDate.of(2026, 11, 5), LocalDate.of(2027, 1, 5), true);

		generate(recurrence);

		List<Row> rows = rows(recurrence);
		assertThat(rows).hasSize(6);
		for (int number = 0; number < 3; number++) {
			final int n = number;
			List<Row> pair = rows.stream().filter(r -> r.number() == n).toList();
			Row out = pair.stream().filter(r -> r.cents() < 0).findFirst().orElseThrow();
			Row in = pair.stream().filter(r -> r.cents() > 0).findFirst().orElseThrow();
			assertThat(pair).hasSize(2);
			assertThat(out.cents()).isEqualTo(-50000);
			assertThat(in.cents()).isEqualTo(50000);
			assertThat(out.account()).isEqualTo(w.checking());
			assertThat(in.account()).isEqualTo(w.savings());
			assertThat(out.date()).isEqualTo(in.date());
			assertThat(out.group()).isNotNull().isEqualTo(in.group()).isEqualTo(expectedGroup(recurrence, n));
		}
		assertThat(rows.stream().map(Row::group).distinct()).hasSize(3);
		assertThat(rows).allSatisfy(r -> {
			assertThat(r.type()).isEqualTo("TRANSFER");
			assertThat(r.category()).isNull();
			assertThat(r.status()).isEqualTo("PENDING");
			assertThat(r.autoClear()).isTrue();
			assertThat(r.description()).isEqualTo("Reserva");
			assertThat(r.notes()).isEqualTo("observação");
			assertThat(r.createdBy()).isEqualTo(w.fx().userId());
			assertThat(r.workspace()).isEqualTo(w.fx().workspaceId());
			assertThat(r.deleted()).isFalse();
		});
	}

	@Test
	void theMarkerIsOffWhenTheRecurrenceHasNoMarker() {
		World w = world();
		UUID recurrence = transfer(w, "WEEKLY", LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 20), false);

		generate(recurrence);

		assertThat(rows(recurrence)).isNotEmpty().allSatisfy(r -> assertThat(r.autoClear()).isFalse());
	}

	@Test
	void theEndDateTheHorizonAndTheLimitOf2100AreRespected() {
		World w = world();

		UUID ended = transfer(w, "WEEKLY", LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 26), false);
		generate(ended);
		assertThat(rows(ended).stream().filter(r -> r.cents() < 0).map(Row::date).toList())
				.containsExactly(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 26));

		UUID far = transfer(w, "YEARLY", LocalDate.of(2100, 12, 31), null, false);
		generate(far);
		assertThat(rows(far)).isEmpty();
		UUID horizon = transfer(w, "MONTHLY", LocalDate.of(2027, 11, 10), null, false);
		generate(horizon);
		assertThat(rows(horizon).stream().filter(r -> r.cents() < 0).map(Row::date).toList()).containsExactly(LocalDate.of(2027, 11, 10));
	}

	// ---- idempotência -----------------------------------------------------------------------------------------

	@Test
	void generatingAgainInsertsNothingAndLeavesEveryPairWhole() {
		World w = world();
		UUID recurrence = transfer(w, "MONTHLY", LocalDate.of(2026, 5, 5), null, false);

		int first = generate(recurrence);
		int second = generate(recurrence);
		int third = generate(recurrence);

		assertThat(first).isPositive().isEven();
		assertThat(second).isZero();
		assertThat(third).isZero();
		assertThat(rows(recurrence)).hasSize(first);
	}

	@Test
	void aDeletedOccurrenceNeverComesBack() {
		World w = world();
		UUID recurrence = transfer(w, "MONTHLY", LocalDate.of(2026, 11, 5), null, false);
		int rowsBefore = generate(recurrence);
		owner.client().sql("update transactions set deleted_at = now() where recurrence_id = :r and occurrence_number = 2")
				.param("r", recurrence).update();

		int inserted = generate(recurrence);

		assertThat(inserted).isZero();
		assertThat(rows(recurrence)).hasSize(rowsBefore);
		assertThat(rows(recurrence).stream().filter(r -> r.number() == 2)).hasSize(2).allMatch(Row::deleted);
	}

	@Test
	void whenTheHorizonMovesOnlyTheNewPairsAreAdded() {
		World w = world();
		UUID recurrence = transfer(w, "MONTHLY", LocalDate.of(2026, 11, 5), null, false);
		generate(recurrence);
		int before = rows(recurrence).size();

		int inserted = owner.client().sql("select generate_recurrence_occurrences(cast(:d as date), cast(:r as uuid))")
				.param("d", TODAY.plusMonths(3)).param("r", recurrence).query(Integer.class).single();

		assertThat(inserted).isEqualTo(6);
		assertThat(rows(recurrence)).hasSize(before + 6);
	}

	@Test
	void simultaneousGenerationsNeverLeaveALooseLegOrADuplicate() throws Exception {
		World w = world();
		UUID recurrence = transfer(w, "WEEKLY", LocalDate.of(2026, 10, 1), null, false);
		int expectedRows = 2 * RecurrenceCalendar.expected(LocalDate.of(2026, 10, 1), "WEEKLY", null, TODAY).size();
		ExecutorService pool = Executors.newFixedThreadPool(8);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < 8; i++) {
				results.add(pool.submit(() -> {
					go.await();
					return generate(recurrence);
				}));
			}
			go.countDown();
			int total = 0;
			for (Future<Integer> result : results) {
				total += result.get(60, TimeUnit.SECONDS);
			}

			assertThat(total).as("cada linha foi inserida por exatamente uma execução").isEqualTo(expectedRows);
		}
		finally {
			pool.shutdownNow();
		}

		List<Row> rows = rows(recurrence);
		assertThat(rows).hasSize(expectedRows);
		assertThat(rows.stream().filter(r -> r.cents() < 0).count()).isEqualTo(rows.stream().filter(r -> r.cents() > 0).count());
		assertThat(rows.stream().map(Row::group).distinct().count()).isEqualTo(expectedRows / 2);
	}

	// ---- quando não gera --------------------------------------------------------------------------------------

	@Test
	void anArchivedOriginOrDestinationPausesTheGenerationAndRestoringResumesIt() {
		World w = world();
		UUID recurrence = transfer(w, "MONTHLY", LocalDate.of(2026, 11, 5), null, false);

		owner.client().sql("update accounts set archived = true where id = :id").param("id", w.savings()).update();
		assertThat(generate(recurrence)).isZero();
		owner.client().sql("update accounts set archived = false where id = :id").param("id", w.savings()).update();
		owner.client().sql("update accounts set archived = true where id = :id").param("id", w.checking()).update();
		assertThat(generate(recurrence)).isZero();
		owner.client().sql("update accounts set archived = false where id = :id").param("id", w.checking()).update();

		assertThat(generate(recurrence)).isPositive();
	}

	@Test
	void anEndedRecurrenceGeneratesNothingAndOneWithAProblemDoesNotStopTheOthers() {
		World w = world();
		World problem = world();
		UUID ended = transfer(w, "MONTHLY", LocalDate.of(2026, 11, 5), null, false);
		owner.client().sql("update recurrences set ended_at = now() where id = :id").param("id", ended).update();
		UUID blocked = transfer(problem, "MONTHLY", LocalDate.of(2026, 11, 5), null, false);
		owner.client().sql("update accounts set archived = true where id = :id").param("id", problem.savings()).update();
		UUID healthy = transfer(w, "MONTHLY", LocalDate.of(2026, 11, 5), null, false);

		int inserted = owner.client().sql("select generate_recurrence_occurrences(cast(:d as date))").param("d", TODAY).query(Integer.class).single();

		assertThat(rows(ended)).isEmpty();
		assertThat(rows(blocked)).isEmpty();
		assertThat(rows(healthy)).isNotEmpty();
		assertThat(inserted).isEqualTo(rows(healthy).size());
	}

	@Test
	void aWorkspaceNeverReceivesAnotherWorkspacesOccurrences() {
		World a = world();
		World b = world();
		UUID ra = transfer(a, "MONTHLY", LocalDate.of(2026, 11, 5), null, false);
		UUID rb = transfer(b, "MONTHLY", LocalDate.of(2026, 11, 5), null, false);

		owner.client().sql("select generate_recurrence_occurrences(cast(:d as date))").param("d", TODAY).query(Integer.class).single();

		assertThat(rows(ra)).isNotEmpty().allSatisfy(r -> assertThat(r.workspace()).isEqualTo(a.fx().workspaceId()));
		assertThat(rows(rb)).isNotEmpty().allSatisfy(r -> assertThat(r.workspace()).isEqualTo(b.fx().workspaceId()));
		assertThat(rows(ra).stream().map(Row::account).distinct()).containsExactlyInAnyOrder(a.checking(), a.savings());
	}

	// ---- receita e despesa seguem iguais ---------------------------------------------------------------------

	@Test
	void anExpenseRecurrenceStillGeneratesOneRowPerNumberWithoutAGroup() {
		World w = world();
		UUID recurrence = expense(w, "MONTHLY", LocalDate.of(2026, 11, 5));

		int inserted = generate(recurrence);

		List<Row> rows = rows(recurrence);
		assertThat(inserted).isEqualTo(RecurrenceCalendar.expected(LocalDate.of(2026, 11, 5), "MONTHLY", null, TODAY).size());
		assertThat(rows).hasSize(inserted).allSatisfy(r -> {
			assertThat(r.type()).isEqualTo("EXPENSE");
			assertThat(r.cents()).isEqualTo(-50000);
			assertThat(r.group()).isNull();
			assertThat(r.category()).isEqualTo(w.expense());
		});
		assertThat(generate(recurrence)).isZero();
	}

}
