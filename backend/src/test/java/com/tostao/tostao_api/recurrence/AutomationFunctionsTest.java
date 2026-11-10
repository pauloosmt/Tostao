package com.tostao.tostao_api.recurrence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * A {@code V11} direto no banco (SC-003, SC-004): o calendário das ocorrências contra um cálculo independente em
 * {@code java.time} (escrito aqui, com aritmética de ano e mês), a idempotência, o que não gera e a efetivação do que
 * venceu, sempre sem passar pela API.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AutomationFunctionsTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	JdbcClient appJdbc;
	@Autowired
	PlatformTransactionManager transactionManager;

	/**
	 * O banco é compartilhado entre os testes, e as funções agem sobre o banco todo: antes de cada teste, as recorrências e os
	 * lançamentos marcados que sobraram de outros testes são deixados de lado (encerradas e efetivados), para as contagens
	 * globais deste serem só as dele.
	 */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
		clearDue(LocalDate.of(2100, 12, 31));
	}

	private record World(Fixture fx, UUID account, UUID expense, UUID income) {
	}

	private World world() {
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		Fixture fx = f.workspaceWithOwner();
		UUID account = f.insertAccount(fx.workspaceId(), "Conta", "CHECKING", 0, false);
		UUID expense = f.insertCategory(fx.workspaceId(), null, "Aluguel", "EXPENSE", false);
		UUID income = f.insertCategory(fx.workspaceId(), null, "Salário", "INCOME", false);
		return new World(fx, account, expense, income);
	}

	private UUID rec(World w, String type, String frequency, LocalDate start, LocalDate end, boolean autoClear, String notes) {
		return owner.client().sql("""
				insert into recurrences (workspace_id, account_id, category_id, type, amount_cents, description,
				                         notes, frequency, start_on, end_on, auto_clear, created_by)
				values (:w, :a, :c, :t, 150000, 'Aluguel', :n, :f, :s, :e, :ac, :u) returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.account())
				.param("c", type.equals("INCOME") ? w.income() : w.expense()).param("t", type).param("n", notes)
				.param("f", frequency).param("s", start).param("e", end).param("ac", autoClear)
				.param("u", w.fx().userId()).query(UUID.class).single();
	}

	private UUID rec(World w, String frequency, LocalDate start) {
		return rec(w, "EXPENSE", frequency, start, null, false, null);
	}

	private int generate(LocalDate today, UUID recurrence) {
		return owner.client().sql("select generate_recurrence_occurrences(cast(:d as date), cast(:r as uuid))")
				.param("d", today).param("r", recurrence).query(Integer.class).single();
	}

	private int generateAll(LocalDate today) {
		return generate(today, null);
	}

	private int clearDue(LocalDate today) {
		return owner.client().sql("select auto_clear_due(cast(:d as date))").param("d", today).query(Integer.class).single();
	}

	private record Occ(int number, LocalDate date) {
	}

	private List<Occ> occurrences(UUID recurrence) {
		return owner.client().sql("select occurrence_number, occurred_on from transactions where recurrence_id = :r "
				+ "order by occurrence_number").param("r", recurrence)
				.query((rs, i) -> new Occ(rs.getInt(1), rs.getObject(2, LocalDate.class))).list();
	}

	// ---- o cálculo independente -------------------------------------------------------------------------------

	/** Soma meses por aritmética de ano e mês, limitando o dia ao fim do mês; sem {@code plusMonths}. */
	private static LocalDate addMonths(LocalDate base, int months) {
		int total = base.getYear() * 12 + (base.getMonthValue() - 1) + months;
		YearMonth target = YearMonth.of(total / 12, total % 12 + 1);
		return target.atDay(Math.min(base.getDayOfMonth(), target.lengthOfMonth()));
	}

	private static LocalDate expectedDate(LocalDate start, String frequency, int n) {
		return switch (frequency) {
			case "WEEKLY" -> LocalDate.ofEpochDay(start.toEpochDay() + 7L * n);
			case "MONTHLY" -> addMonths(start, n);
			default -> addMonths(start, 12 * n);
		};
	}

	/** Todas as ocorrências esperadas: de 0 até a primeira data depois do horizonte, do fim ou de 2100-12-31. */
	private static List<Occ> expected(LocalDate start, String frequency, LocalDate end, LocalDate today) {
		LocalDate horizon = addMonths(today, 12);
		List<Occ> list = new ArrayList<>();
		for (int n = 0; n < 10_000; n++) {
			LocalDate date = expectedDate(start, frequency, n);
			if (date.isAfter(horizon) || (end != null && date.isAfter(end)) || date.isAfter(LocalDate.of(2100, 12, 31))) {
				break;
			}
			list.add(new Occ(n, date));
		}
		return list;
	}

	// ---- calendário -------------------------------------------------------------------------------------------

	@Test
	void theCalendarMatchesTheIndependentCalculationForEveryFrequencyAndEveryEndOfMonth() {
		World w = world();
		LocalDate[] starts = { TODAY, TODAY.plusDays(1), TODAY.minusDays(1), LocalDate.of(2026, 11, 1),
				LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 29),
				LocalDate.of(2027, 1, 30), LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28),
				LocalDate.of(2028, 1, 31), LocalDate.of(2028, 2, 29), LocalDate.of(2026, 1, 1), LocalDate.of(2025, 12, 15),
				LocalDate.of(2026, 5, 31) };
		int total = 0;
		List<UUID> ids = new ArrayList<>();
		List<List<Occ>> wanted = new ArrayList<>();
		for (String frequency : new String[] { "WEEKLY", "MONTHLY", "YEARLY" }) {
			for (LocalDate start : starts) {
				ids.add(rec(w, frequency, start));
				wanted.add(expected(start, frequency, null, TODAY));
				total += wanted.get(wanted.size() - 1).size();
			}
		}

		assertThat(generateAll(TODAY)).isEqualTo(total);

		for (int i = 0; i < ids.size(); i++) {
			assertThat(occurrences(ids.get(i))).as("recorrência %d", i).isEqualTo(wanted.get(i));
		}
	}

	@Test
	void theEndOfMonthAdjustmentNeverAccumulates() {
		World w = world();
		UUID r = rec(w, "MONTHLY", LocalDate.of(2027, 1, 31));
		UUID leap = rec(w, "YEARLY", LocalDate.of(2028, 2, 29));

		generateAll(LocalDate.of(2027, 1, 1));
		generateAll(LocalDate.of(2031, 6, 1));

		assertThat(occurrences(r).subList(0, 5)).extracting(Occ::date).containsExactly(LocalDate.of(2027, 1, 31),
				LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31), LocalDate.of(2027, 4, 30), LocalDate.of(2027, 5, 31));
		assertThat(occurrences(leap)).extracting(Occ::date).containsExactly(LocalDate.of(2028, 2, 29),
				LocalDate.of(2029, 2, 28), LocalDate.of(2030, 2, 28), LocalDate.of(2031, 2, 28), LocalDate.of(2032, 2, 29));
	}

	@Test
	void theHorizonIsExactlyTwelveMonthsAfterToday() {
		World w = world();
		UUID onTheLimit = rec(w, "MONTHLY", TODAY);
		UUID oneDayLater = rec(w, "MONTHLY", TODAY.plusDays(1));

		generateAll(TODAY);

		assertThat(occurrences(onTheLimit)).hasSize(13);
		assertThat(occurrences(onTheLimit).get(12).date()).isEqualTo(LocalDate.of(2027, 11, 10));
		assertThat(occurrences(oneDayLater)).hasSize(12);
		assertThat(occurrences(oneDayLater).get(11).date()).isEqualTo(LocalDate.of(2027, 10, 11));
	}

	@Test
	void aRecurrenceStartingAfterTheHorizonGeneratesNothingUntilItEntersTheHorizon() {
		World w = world();
		UUID r = rec(w, "MONTHLY", LocalDate.of(2028, 1, 15));

		assertThat(generateAll(TODAY)).isZero();
		assertThat(occurrences(r)).isEmpty();
		// um ano e um mês depois, a primeira data entra no horizonte
		assertThat(generateAll(LocalDate.of(2027, 1, 15))).isEqualTo(1);
		assertThat(occurrences(r)).extracting(Occ::date).containsExactly(LocalDate.of(2028, 1, 15));
	}

	@Test
	void theEndDateAndTheYear2100AreLimits() {
		World w = world();
		UUID ended = rec(w, "EXPENSE", "MONTHLY", TODAY, TODAY.plusMonths(2), false, null);
		UUID sameDay = rec(w, "EXPENSE", "WEEKLY", TODAY, TODAY, false, null);
		UUID weekly2100 = rec(w, "WEEKLY", LocalDate.of(2100, 12, 10));
		UUID yearly2100 = rec(w, "YEARLY", LocalDate.of(2099, 12, 31));

		generateAll(TODAY);
		generateAll(LocalDate.of(2100, 6, 1));

		assertThat(occurrences(ended)).extracting(Occ::date).containsExactly(TODAY, LocalDate.of(2026, 12, 10),
				LocalDate.of(2027, 1, 10));
		assertThat(occurrences(sameDay)).extracting(Occ::date).containsExactly(TODAY);
		// 2100-12-10, 17, 24 e 31; a de 2101-01-07 passaria do limite
		assertThat(occurrences(weekly2100)).extracting(Occ::date).containsExactly(LocalDate.of(2100, 12, 10),
				LocalDate.of(2100, 12, 17), LocalDate.of(2100, 12, 24), LocalDate.of(2100, 12, 31));
		assertThat(occurrences(yearly2100)).extracting(Occ::date).containsExactly(LocalDate.of(2099, 12, 31),
				LocalDate.of(2100, 12, 31));
	}

	// ---- a linha gerada, idempotência e o que não gera --------------------------------------------------------

	@Test
	void theGeneratedLinesCopyTheRecurrence() {
		World w = world();
		UUID expense = rec(w, "EXPENSE", "MONTHLY", TODAY, TODAY.plusMonths(1), true, "Contrato de 12 meses");
		UUID income = rec(w, "INCOME", "MONTHLY", TODAY, TODAY.plusMonths(1), false, null);

		generateAll(TODAY);

		record Row(UUID ws, UUID account, UUID category, String type, long cents, String description, String notes,
				String status, boolean autoClear, UUID createdBy) {
		}
		String sql = """
				select workspace_id, account_id, category_id, type, amount_cents, description, notes, status, auto_clear,
				       created_by from transactions where recurrence_id = :r order by occurrence_number""";
		List<Row> expenseRows = owner.client().sql(sql).param("r", expense).query((rs, i) -> new Row(
				rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
				rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBoolean(9),
				rs.getObject(10, UUID.class))).list();
		List<Row> incomeRows = owner.client().sql(sql).param("r", income).query((rs, i) -> new Row(
				rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4),
				rs.getLong(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBoolean(9),
				rs.getObject(10, UUID.class))).list();

		assertThat(expenseRows).hasSize(2).allSatisfy(r -> {
			assertThat(r.ws()).isEqualTo(w.fx().workspaceId());
			assertThat(r.account()).isEqualTo(w.account());
			assertThat(r.category()).isEqualTo(w.expense());
			assertThat(r.type()).isEqualTo("EXPENSE");
			assertThat(r.cents()).as("despesa negativa").isEqualTo(-150_000);
			assertThat(r.description()).isEqualTo("Aluguel");
			assertThat(r.notes()).isEqualTo("Contrato de 12 meses");
			assertThat(r.status()).isEqualTo("PENDING");
			assertThat(r.autoClear()).isTrue();
			assertThat(r.createdBy()).isEqualTo(w.fx().userId());
		});
		assertThat(incomeRows).hasSize(2).allSatisfy(r -> {
			assertThat(r.category()).isEqualTo(w.income());
			assertThat(r.type()).isEqualTo("INCOME");
			assertThat(r.cents()).as("receita positiva").isEqualTo(150_000);
			assertThat(r.notes()).isNull();
			assertThat(r.autoClear()).isFalse();
			assertThat(r.status()).isEqualTo("PENDING");
		});
	}

	@Test
	void runningTwiceCreatesNothingAndADeletedOccurrenceNeverComesBack() {
		World w = world();
		UUID r = rec(w, "MONTHLY", TODAY);

		assertThat(generate(TODAY, r)).isEqualTo(13);
		assertThat(generate(TODAY, r)).isZero();
		assertThat(generateAll(TODAY)).isZero();

		owner.client().sql("update transactions set deleted_at = now() where recurrence_id = :r and occurrence_number = 3")
				.param("r", r).update();
		assertThat(generateAll(TODAY)).as("a excluída não volta").isZero();
		assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = :r").param("r", r)
				.query(Long.class).single()).isEqualTo(13L);
		assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = :r and deleted_at is null")
				.param("r", r).query(Long.class).single()).isEqualTo(12L);
	}

	@Test
	void anEditedOccurrenceIsNeitherRecreatedNorDuplicated() {
		World w = world();
		UUID r = rec(w, "MONTHLY", TODAY);
		generate(TODAY, r);
		// a pessoa muda o valor e move a data da ocorrência 2: ela continua sendo a 2
		owner.client().sql("update transactions set amount_cents = -1, occurred_on = date '2027-12-31' "
				+ "where recurrence_id = :r and occurrence_number = 2").param("r", r).update();

		assertThat(generateAll(TODAY)).isZero();
		assertThat(owner.client().sql("select amount_cents from transactions where recurrence_id = :r and occurrence_number = 2")
				.param("r", r).query(Long.class).single()).isEqualTo(-1L);
	}

	@Test
	void anEndedRecurrenceAnArchivedAccountAndAnArchivedCategoryGenerateNothing() {
		World w = world();
		UUID ended = rec(w, "MONTHLY", TODAY);
		owner.client().sql("update recurrences set ended_at = now() where id = :r").param("r", ended).update();
		assertThat(generateAll(TODAY)).isZero();

		World archivedAccount = world();
		UUID byAccount = rec(archivedAccount, "MONTHLY", TODAY);
		owner.client().sql("update accounts set archived = true where id = :a").param("a", archivedAccount.account()).update();
		assertThat(generate(TODAY, byAccount)).isZero();
		owner.client().sql("update accounts set archived = false where id = :a").param("a", archivedAccount.account()).update();
		assertThat(generate(TODAY, byAccount)).as("desarquivada, volta a gerar").isEqualTo(13);

		World archivedCategory = world();
		UUID byCategory = rec(archivedCategory, "MONTHLY", TODAY);
		owner.client().sql("update categories set archived = true where id = :c").param("c", archivedCategory.expense()).update();
		assertThat(generate(TODAY, byCategory)).isZero();
		owner.client().sql("update categories set archived = false where id = :c").param("c", archivedCategory.expense()).update();
		assertThat(generate(TODAY, byCategory)).isEqualTo(13);
	}

	@Test
	void aSingleRecurrenceOnlyGeneratesItself() {
		World w = world();
		UUID one = rec(w, "MONTHLY", TODAY);
		UUID two = rec(w, "MONTHLY", TODAY);

		assertThat(generate(TODAY, one)).isEqualTo(13);

		assertThat(occurrences(one)).hasSize(13);
		assertThat(occurrences(two)).isEmpty();
		assertThat(generateAll(TODAY)).isEqualTo(13);
	}

	@Test
	void theGenerationNeverCrossesWorkspaces() {
		World a = world();
		World b = world();
		UUID ra = rec(a, "MONTHLY", TODAY);
		UUID rb = rec(b, "WEEKLY", TODAY);

		generateAll(TODAY);

		for (World world : new World[] { a, b }) {
			UUID r = world == a ? ra : rb;
			long wrong = owner.client().sql("""
					select count(*) from transactions t
					join accounts ac on ac.id = t.account_id
					join categories ca on ca.id = t.category_id
					where t.recurrence_id = :r
					  and (t.workspace_id <> :w or ac.workspace_id <> :w or ca.workspace_id <> :w)""")
					.param("r", r).param("w", world.fx().workspaceId()).query(Long.class).single();
			assertThat(wrong).isZero();
		}
		assertThat(owner.client().sql("select count(*) from transactions where workspace_id = :w").param("w", a.fx().workspaceId())
				.query(Long.class).single()).isEqualTo(13L);
		assertThat(owner.client().sql("select count(*) from transactions where workspace_id = :w").param("w", b.fx().workspaceId())
				.query(Long.class).single()).isEqualTo(occurrences(rb).size());
	}

	// ---- efetivar o que venceu --------------------------------------------------------------------------------

	private UUID marked(World w, LocalDate date, String status, boolean autoClear, boolean deleted) {
		return owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, notes, created_by, status, auto_clear, deleted_at)
				values (:w, :a, :c, 'EXPENSE', -1000, :d, 'Marcado', 'obs', :u, :st, :ac,
				        case when :x then now() else null end) returning id""")
				.param("w", w.fx().workspaceId()).param("a", w.account()).param("c", w.expense()).param("d", date)
				.param("u", w.fx().userId()).param("st", status).param("ac", autoClear).param("x", deleted)
				.query(UUID.class).single();
	}

	private String statusOf(UUID id) {
		return owner.client().sql("select status || '|' || auto_clear from transactions where id = :id").param("id", id)
				.query(String.class).single();
	}

	@Test
	void onlyAPendingMarkedLiveLineWithADateUpToTodayIsCleared() {
		World w = world();
		UUID today = marked(w, TODAY, "PENDING", true, false);
		UUID past = marked(w, TODAY.minusDays(40), "PENDING", true, false);
		UUID future = marked(w, TODAY.plusDays(1), "PENDING", true, false);
		UUID unmarked = marked(w, TODAY.minusDays(5), "PENDING", false, false);
		UUID cleared = marked(w, TODAY.minusDays(5), "CLEARED", false, false);
		UUID deleted = marked(w, TODAY.minusDays(5), "PENDING", true, true);

		assertThat(clearDue(TODAY)).isEqualTo(2);

		assertThat(statusOf(today)).isEqualTo("CLEARED|false");
		assertThat(statusOf(past)).isEqualTo("CLEARED|false");
		assertThat(statusOf(future)).isEqualTo("PENDING|true");
		assertThat(statusOf(unmarked)).isEqualTo("PENDING|false");
		assertThat(statusOf(cleared)).isEqualTo("CLEARED|false");
		assertThat(statusOf(deleted)).isEqualTo("PENDING|true");
		assertThat(clearDue(TODAY)).as("uso único: nada mais a efetivar").isZero();
		// um dia depois, a que era futura vence
		assertThat(clearDue(TODAY.plusDays(1))).isEqualTo(1);
		assertThat(statusOf(future)).isEqualTo("CLEARED|false");
	}

	@Test
	void clearingChangesOnlyTheStatusTheMarkerAndTheUpdateTime() {
		World w = world();
		UUID id = marked(w, TODAY, "PENDING", true, false);
		String before = owner.client().sql("""
				select concat_ws(',', workspace_id, account_id, category_id, type, amount_cents, occurred_on, description,
				                 notes, created_by, created_at, deleted_at) from transactions where id = :id""")
				.param("id", id).query(String.class).single();
		Object updatedBefore = owner.client().sql("select updated_at from transactions where id = :id").param("id", id)
				.query((rs, i) -> rs.getTimestamp(1)).single();

		clearDue(TODAY);

		String after = owner.client().sql("""
				select concat_ws(',', workspace_id, account_id, category_id, type, amount_cents, occurred_on, description,
				                 notes, created_by, created_at, deleted_at) from transactions where id = :id""")
				.param("id", id).query(String.class).single();
		Object updatedAfter = owner.client().sql("select updated_at from transactions where id = :id").param("id", id)
				.query((rs, i) -> rs.getTimestamp(1)).single();
		assertThat(after).isEqualTo(before);
		assertThat(updatedAfter).isNotEqualTo(updatedBefore);
	}

	@Test
	void bothLegsOfAMarkedTransferAreClearedTogether() {
		World w = world();
		UUID other = new WorkspaceFixtures(owner, tokens).insertAccount(w.fx().workspaceId(), "Poupança", "SAVINGS", 0, false);
		UUID group = UUID.randomUUID();
		List<UUID> legs = new ArrayList<>();
		for (long cents : new long[] { -3000, 3000 }) {
			legs.add(owner.client().sql("""
					insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description,
					                          transfer_group_id, created_by, status, auto_clear)
					values (:w, :a, 'TRANSFER', :m, :d, 'Reserva', :g, :u, 'PENDING', true) returning id""")
					.param("w", w.fx().workspaceId()).param("a", cents < 0 ? w.account() : other).param("m", cents)
					.param("d", TODAY).param("g", group).param("u", w.fx().userId()).query(UUID.class).single());
		}

		assertThat(clearDue(TODAY)).isEqualTo(2);

		assertThat(legs).extracting(this::statusOf).containsOnly("CLEARED|false");
	}

	@Test
	void theClearingNeverCrossesWorkspaces() {
		World a = world();
		World b = world();
		UUID inA = marked(a, TODAY, "PENDING", true, false);
		UUID inB = marked(b, TODAY, "PENDING", true, false);

		assertThat(clearDue(TODAY)).isEqualTo(2);

		assertThat(statusOf(inA)).isEqualTo("CLEARED|false");
		assertThat(statusOf(inB)).isEqualTo("CLEARED|false");
		// e só as linhas das duas mudaram: um terceiro workspace sem marcador não é tocado
		World c = world();
		UUID quiet = marked(c, TODAY, "PENDING", false, false);
		clearDue(TODAY);
		assertThat(statusOf(quiet)).isEqualTo("PENDING|false");
	}

	// ---- quem pode executar -----------------------------------------------------------------------------------

	@Test
	void onlyTheApplicationRoleExecutesTheFunctionsAndTheyFixTheSearchPath() {
		for (String function : new String[] { "generate_recurrence_occurrences", "auto_clear_due" }) {
			boolean publicCanExecute = owner.client().sql("""
					select exists (select 1 from pg_proc p, aclexplode(p.proacl) a
					               where p.proname = :f and a.grantee = 0 and a.privilege_type = 'EXECUTE')""")
					.param("f", function).query(Boolean.class).single();
			boolean appCanExecute = owner.client().sql("""
					select exists (select 1 from pg_proc p, aclexplode(p.proacl) a
					               join pg_roles r on r.oid = a.grantee
					               where p.proname = :f and r.rolname = 'tostao_app' and a.privilege_type = 'EXECUTE')""")
					.param("f", function).query(Boolean.class).single();
			boolean definer = owner.client().sql("select prosecdef from pg_proc where proname = :f").param("f", function)
					.query(Boolean.class).single();
			String config = owner.client().sql("select array_to_string(proconfig, ',') from pg_proc where proname = :f")
					.param("f", function).query(String.class).single();

			assertThat(publicCanExecute).as(function + ": PUBLIC não executa").isFalse();
			assertThat(appCanExecute).as(function + ": a aplicação executa").isTrue();
			assertThat(definer).as(function + ": SECURITY DEFINER").isTrue();
			assertThat(config).as(function + ": search_path fixo").contains("search_path");
		}
	}

	@Test
	void theApplicationRoleRunsTheFunctionsWithoutAnyPersonContext() {
		World w = world();
		UUID r = rec(w, "MONTHLY", TODAY);
		UUID due = marked(w, TODAY, "PENDING", true, false);

		Integer generated = new TransactionTemplate(transactionManager).execute(status -> appJdbc
				.sql("select generate_recurrence_occurrences(cast(:d as date), cast(:r as uuid))").param("d", TODAY)
				.param("r", r).query(Integer.class).single());
		Integer cleared = new TransactionTemplate(transactionManager).execute(status -> appJdbc
				.sql("select auto_clear_due(cast(:d as date))").param("d", TODAY).query(Integer.class).single());

		assertThat(generated).isEqualTo(13);
		assertThat(cleared).isGreaterThanOrEqualTo(1);
		assertThat(statusOf(due)).isEqualTo("CLEARED|false");
		// e sem contexto, a mesma role não enxerga nada por SELECT direto: o RLS segue valendo para ela
		Long visible = new TransactionTemplate(transactionManager)
				.execute(status -> appJdbc.sql("select count(*) from recurrences").query(Long.class).single());
		assertThat(visible).isZero();
	}

}
