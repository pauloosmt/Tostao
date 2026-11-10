package com.tostao.tostao_api.recurrence;

import static com.tostao.tostao_api.recurrence.RecurrenceScenario.createRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.endRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.recurrencesFingerprint;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.today;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.valid;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.with;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static com.tostao.tostao_api.transaction.PendingScenario.edit;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsFingerprint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.automation.AutomationService;
import com.tostao.tostao_api.automation.AutomationService.RunResult;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.transaction.PendingScenario;

/**
 * SC-004, SC-005 e SC-006 sob concorrência real: a criação é atômica, execuções simultâneas nunca duplicam, encerrar e
 * gerar ao mesmo tempo nunca deixa uma ocorrência futura pendente, e a rotina convive com a efetivação manual e com a
 * edição sem estado inconsistente nem impasse.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RecurrenceConcurrencyTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	AutomationService automation;

	/** O banco é compartilhado: deixa de lado os lançamentos marcados e as recorrências que sobraram de outros testes. */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
		owner.client().sql("select auto_clear_due(date '2100-12-31')").query(Integer.class).single();
	}

	private RecurrenceScenario scenario() {
		return RecurrenceScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	private record Answer(int status, String body) {
	}

	/** Roda as ações ao mesmo tempo (largada sincronizada) e devolve as respostas na ordem. */
	private static <T> List<T> together(List<Callable<T>> actions) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(actions.size());
		CountDownLatch ready = new CountDownLatch(actions.size());
		CountDownLatch go = new CountDownLatch(1);
		List<Future<T>> futures = new ArrayList<>();
		for (var action : actions) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				return action.call();
			}));
		}
		ready.await(30, TimeUnit.SECONDS);
		go.countDown();
		List<T> answers = new ArrayList<>();
		for (Future<T> future : futures) {
			answers.add(future.get(90, TimeUnit.SECONDS));
		}
		pool.shutdown();
		return answers;
	}

	private static Answer answer(ResultActions result) throws Exception {
		var response = result.andReturn().getResponse();
		return new Answer(response.getStatus(), response.getContentAsString());
	}

	private long count(String sql, Object... params) {
		var spec = owner.client().sql(sql);
		for (int i = 0; i < params.length; i++) {
			spec = spec.param("p" + i, params[i]);
		}
		return spec.query(Long.class).single();
	}

	/** Insere, pela conexão do dono, uma recorrência de hoje sem gerar ocorrência alguma. */
	private UUID bareRecurrence(RecurrenceScenario s, boolean autoClear) {
		return owner.client().sql("""
				insert into recurrences (workspace_id, account_id, category_id, type, amount_cents, description,
				                         frequency, start_on, auto_clear, created_by)
				values (:w, :a, :c, 'EXPENSE', 150000, 'Aluguel', 'MONTHLY', :s, :ac, :u) returning id""")
				.param("w", s.owner().workspaceId()).param("a", s.checking()).param("c", s.rent()).param("s", today())
				.param("ac", autoClear).param("u", s.owner().userId()).query(UUID.class).single();
	}

	// ---- atomicidade da criação -------------------------------------------------------------------------------

	@Test
	void aFailureInTheMiddleOfTheCreationLeavesNeitherTheRecurrenceNorAnyOccurrence() throws Exception {
		RecurrenceScenario s = scenario();
		UUID w = s.owner().workspaceId();
		String transactions = transactionsFingerprint(owner, w);
		String recurrences = recurrencesFingerprint(owner, w);
		// um gatilho temporário (só deste workspace) faz a ocorrência 3 falhar, sem gancho de teste na produção
		owner.client().sql("""
				create function recurrence_test_failure() returns trigger language plpgsql as $f$
				begin
				  if new.workspace_id = '%s' and new.occurrence_number = 3 then
				    raise exception 'falha forçada na ocorrência 3';
				  end if;
				  return new;
				end $f$""".formatted(w)).update();
		owner.client().sql("create trigger recurrence_test_failure before insert on transactions "
				+ "for each row execute function recurrence_test_failure()").update();
		try {
			assertThatThrownBy(() -> createRecurrence(mvc, s.owner(), with(valid(s)))).hasStackTraceContaining("falha forçada");

			// as ocorrências 0, 1 e 2 e a própria recorrência foram gravadas antes da falha e foram desfeitas junto
			assertThat(count("select count(*) from transactions where workspace_id = :p0", w)).isZero();
			assertThat(count("select count(*) from recurrences where workspace_id = :p0", w)).isZero();
			assertThat(transactionsFingerprint(owner, w)).isEqualTo(transactions);
			assertThat(recurrencesFingerprint(owner, w)).isEqualTo(recurrences);
		}
		finally {
			owner.client().sql("drop trigger if exists recurrence_test_failure on transactions").update();
			owner.client().sql("drop function if exists recurrence_test_failure()").update();
		}

		// sem o gatilho, a mesma criação funciona
		createRecurrence(mvc, s.owner(), with(valid(s))).andExpect(status().isCreated());
		assertThat(count("select count(*) from recurrences where workspace_id = :p0", w)).isEqualTo(1L);
	}

	// ---- execuções simultâneas --------------------------------------------------------------------------------

	@Test
	void twentySimultaneousRunsGenerateEachOccurrenceExactlyOnce() throws Exception {
		RecurrenceScenario s = scenario();
		UUID r = bareRecurrence(s, true);
		List<Callable<RunResult>> actions = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			actions.add(() -> automation.runOnce(today()));
		}

		List<RunResult> results = together(actions);

		// quem não conseguiu a trava foi pulado; a soma do que foi gerado é cada ocorrência uma só vez
		assertThat(results.stream().filter(x -> !x.skipped()).mapToInt(RunResult::generated).sum()).isEqualTo(13);
		assertThat(count("select count(*) from transactions where recurrence_id = :p0", r)).isEqualTo(13L);
		assertThat(count("select count(*) from (select 1 from transactions where recurrence_id = :p0 "
				+ "group by occurrence_number having count(*) > 1) x", r)).isZero();
		// a de hoje, com o marcador, foi efetivada uma só vez
		assertThat(results.stream().filter(x -> !x.skipped()).mapToInt(RunResult::cleared).sum()).isEqualTo(1);
	}

	// ---- encerrar x gerar -------------------------------------------------------------------------------------

	@Test
	void endingAndGeneratingAtTheSameTimeNeverLeavesAFuturePendingOccurrence() throws Exception {
		RecurrenceScenario s = scenario();

		for (int round = 0; round < 30; round++) {
			UUID r = bareRecurrence(s, false);
			List<Callable<Answer>> actions = List.of(
					() -> answer(endRecurrence(mvc, s.owner(), r)),
					() -> {
						owner.client().sql("select generate_recurrence_occurrences(cast(:d as date), cast(:r as uuid))")
								.param("d", today()).param("r", r).query(Integer.class).single();
						return new Answer(200, "");
					});

			List<Answer> answers = together(actions);

			assertThat(answers.get(0).status()).as("rodada " + round).isEqualTo(200);
			assertThat(count("select count(*) from recurrences where id = :p0 and ended_at is not null", r)).isEqualTo(1L);
			// nunca sobra ocorrência pendente futura, qualquer que tenha sido a ordem
			assertThat(count("select count(*) from transactions where recurrence_id = :p0 and status = 'PENDING' "
					+ "and deleted_at is null and occurred_on > :p1", r, today())).as("rodada " + round).isZero();
		}
	}

	// ---- efetivar à mão x rotina, editar x rotina -------------------------------------------------------------

	@Test
	void clearingByHandAndTheAutomaticRunAtTheSameTimeClearTheTransactionOnce() throws Exception {
		RecurrenceScenario s = scenario();
		UUID w = s.owner().workspaceId();

		for (int round = 0; round < 30; round++) {
			UUID id = owner.client().sql("""
					insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
					                          description, created_by, status, auto_clear)
					values (:w, :a, :c, 'EXPENSE', -1000, :d, 'Corrida', :u, 'PENDING', true) returning id""")
					.param("w", w).param("a", s.checking()).param("c", s.rent()).param("d", today())
					.param("u", s.owner().userId()).query(UUID.class).single();

			List<Callable<Answer>> actions = List.of(
					() -> answer(clear(mvc, s.owner(), id)),
					() -> {
						automation.runOnce(today());
						return new Answer(200, "");
					});
			List<Answer> answers = together(actions);

			// a efetivação manual é idempotente: responde 200 antes ou depois da rotina
			assertThat(answers.get(0).status()).as("rodada " + round).isEqualTo(200);
			assertThat(owner.client().sql("select status || '|' || auto_clear from transactions where id = :id").param("id", id)
					.query(String.class).single()).as("rodada " + round).isEqualTo("CLEARED|false");
		}
	}

	@Test
	void editingAMarkedTransferAndTheAutomaticRunAtTheSameTimeKeepBothLegsConsistent() throws Exception {
		PendingScenario s = PendingScenario.create(new WorkspaceFixtures(owner, tokens));

		for (int round = 0; round < 30; round++) {
			String transfer = PendingScenario.transferBody(s.checking(), s.savings(), 10_000, today().toString(), "Reserva " + round, "PENDING");
			UUID leg = UUID.fromString(JsonPath.read(PendingScenario.create(mvc, s.owner(),
					transfer.substring(0, transfer.length() - 1) + ",\"autoClear\":true}").andExpect(status().isCreated())
					.andReturn().getResponse().getContentAsString(), "$.id"));
			String edited = "{\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":12000,\"occurredOn\":\"%s\",\"description\":\"Reserva %d\"}"
					.formatted(s.checking(), s.savings(), today(), round);

			List<Callable<Answer>> actions = List.of(
					() -> answer(edit(mvc, s.owner(), leg, edited)),
					() -> {
						automation.runOnce(today());
						return new Answer(200, "");
					});
			List<Answer> answers = together(actions);

			assertThat(answers.get(0).status()).as("rodada " + round).isEqualTo(200);
			// as duas pontas sempre no mesmo estado, no mesmo valor e sem marcador
			assertThat(owner.client().sql("select string_agg(status || '|' || auto_clear || '|' || abs(amount_cents), ',' order by amount_cents) "
					+ "from transactions where transfer_group_id = (select transfer_group_id from transactions where id = :id)")
					.param("id", leg).query(String.class).single()).as("rodada " + round)
					.isEqualTo("CLEARED|false|12000,CLEARED|false|12000");
		}
	}

}
