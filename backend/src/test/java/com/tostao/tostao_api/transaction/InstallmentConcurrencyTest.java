package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.InstallmentScenario.cancel;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchase;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchaseBody;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
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
import com.tostao.tostao_api.support.WorkspaceFixtures;

/**
 * SC-004 e SC-005 sob concorrência real: o registro é atômico, e o cancelamento nunca desfaz uma efetivação que
 * ocorre ao mesmo tempo nem trava em impasse.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InstallmentConcurrencyTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private record Answer(int status, String body) {
	}

	/** Roda as ações ao mesmo tempo (largada sincronizada) e devolve as respostas na ordem. */
	private static List<Answer> together(List<Callable<Answer>> actions) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(actions.size());
		CountDownLatch ready = new CountDownLatch(actions.size());
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Answer>> futures = new ArrayList<>();
		for (var action : actions) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				return action.call();
			}));
		}
		ready.await(30, TimeUnit.SECONDS);
		go.countDown();
		List<Answer> answers = new ArrayList<>();
		for (Future<Answer> future : futures) {
			answers.add(future.get(90, TimeUnit.SECONDS));
		}
		pool.shutdown();
		return answers;
	}

	private static Answer answer(ResultActions result) throws Exception {
		var response = result.andReturn().getResponse();
		return new Answer(response.getStatus(), response.getContentAsString());
	}

	private UUID buy(InstallmentScenario s, long total, int count) throws Exception {
		String json = purchase(mvc, s.owner(), purchaseBody(s.checking(), s.gadgets(), total, count, "2026-11-10", "Compra",
				null)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(JsonPath.read(json, "$.groupId"));
	}

	private long countRows(UUID workspaceId) {
		return owner.client().sql("select count(*) from transactions where workspace_id = :w").param("w", workspaceId)
				.query(Long.class).single();
	}

	@Test
	void aFailureInTheMiddleOfTheRegistrationLeavesNoInstallmentBehind() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		String before = transactionsFingerprint(owner, w);
		// um gatilho temporário (só deste workspace) faz a 3ª das 5 parcelas falhar, sem gancho de teste na produção
		owner.client().sql("""
				create function installment_test_failure() returns trigger language plpgsql as $f$
				begin
				  if new.workspace_id = '%s' and new.description like '%%(3/5)' then
				    raise exception 'falha forçada na terceira parcela';
				  end if;
				  return new;
				end $f$""".formatted(w)).update();
		owner.client().sql("create trigger installment_test_failure before insert on transactions "
				+ "for each row execute function installment_test_failure()").update();
		try {
			assertThatThrownBy(() -> purchase(mvc, s.owner(), purchaseBody(s.checking(), s.gadgets(), 50_000, 5,
					"2026-11-10", "Falha", null))).hasStackTraceContaining("falha forçada");

			// as duas primeiras parcelas foram inseridas antes da falha e foram desfeitas junto
			assertThat(countRows(w)).isZero();
			assertThat(transactionsFingerprint(owner, w)).isEqualTo(before);
		}
		finally {
			owner.client().sql("drop trigger if exists installment_test_failure on transactions").update();
			owner.client().sql("drop function if exists installment_test_failure()").update();
		}

		// sem o gatilho, a mesma compra funciona
		buy(s, 50_000, 5);
		assertThat(countRows(w)).isEqualTo(5L);
	}

	@Test
	void twentySimultaneousCancellationsOfTheSamePlanCancelEachPendingInstallmentExactlyOnce() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		UUID group = buy(s, 100_000, 10); // 1 efetivada e 9 pendentes
		List<Callable<Answer>> actions = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			actions.add(() -> answer(cancel(mvc, s.owner(), group)));
		}

		List<Answer> answers = together(actions);

		assertThat(answers).extracting(Answer::status).containsOnly(200);
		long cancelled = answers.stream().mapToLong(a -> ((Number) JsonPath.read(a.body(), "$.cancelled")).longValue()).sum();
		assertThat(cancelled).as("cada pendente é cancelada uma só vez").isEqualTo(9);
		assertThat(owner.client().sql("select count(*) from transactions where installment_group_id = :g and deleted_at is null")
				.param("g", group).query(Long.class).single()).isEqualTo(1L);
	}

	@Test
	void cancellingAndClearingTheSameInstallmentNeverBothHappen() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		for (int round = 0; round < 30; round++) {
			UUID group = buy(s, 300_000, 3); // 1 efetivada, 2 e 3 pendentes
			UUID second = owner.client().sql("select id from transactions where installment_group_id = :g and installment_number = 2")
					.param("g", group).query(UUID.class).single();

			List<Answer> answers = together(List.of(
					() -> answer(clear(mvc, s.owner(), second)),
					() -> answer(cancel(mvc, s.owner(), group))));

			int clearStatus = answers.get(0).status();
			assertThat(answers.get(1).status()).as("rodada " + round).isEqualTo(200);
			long cancelled = ((Number) JsonPath.read(answers.get(1).body(), "$.cancelled")).longValue();
			boolean deleted = owner.client().sql("select deleted_at is not null from transactions where id = :id")
					.param("id", second).query(Boolean.class).single();
			String state = owner.client().sql("select status from transactions where id = :id").param("id", second)
					.query(String.class).single();
			if (clearStatus == 200) {
				// efetivada antes: o cancelamento a deixa de fora e leva só a 3ª
				assertThat(deleted).as("rodada " + round).isFalse();
				assertThat(state).as("rodada " + round).isEqualTo("CLEARED");
				assertThat(cancelled).as("rodada " + round).isEqualTo(1);
			}
			else {
				// cancelada antes: a efetivação encontra a parcela excluída
				assertThat(clearStatus).as("rodada " + round).isEqualTo(404);
				assertThat(deleted).as("rodada " + round).isTrue();
				assertThat(state).as("rodada " + round).isEqualTo("PENDING");
				assertThat(cancelled).as("rodada " + round).isEqualTo(2);
			}
		}
	}

	@Test
	void tenSimultaneousRegistrationsOnTheSameAccountCreateTenCompletePlans() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		List<Callable<Answer>> actions = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			actions.add(() -> answer(purchase(mvc, s.owner(), purchaseBody(s.checking(), s.gadgets(), 50_000, 5, "2026-11-10",
					"Simultânea", null))));
		}

		List<Answer> answers = together(actions);

		assertThat(answers).extracting(Answer::status).containsOnly(201);
		List<String> groups = answers.stream().map(a -> (String) JsonPath.read(a.body(), "$.groupId")).distinct().toList();
		assertThat(groups).hasSize(10);
		for (Answer a : answers) {
			assertThat(JsonPath.<List<?>>read(a.body(), "$.installments")).hasSize(5);
		}
		assertThat(countRows(s.owner().workspaceId())).isEqualTo(50L);
		// e nenhuma parcela repetiu número dentro do seu grupo
		assertThat(owner.client().sql("select count(*) from (select 1 from transactions where workspace_id = :w "
				+ "group by installment_group_id, installment_number having count(*) > 1) x")
				.param("w", s.owner().workspaceId()).query(Long.class).single()).isZero();
	}

}
