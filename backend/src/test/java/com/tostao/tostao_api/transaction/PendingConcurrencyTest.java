package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.transaction.PendingScenario.account;
import static com.tostao.tostao_api.transaction.PendingScenario.body;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static com.tostao.tostao_api.transaction.PendingScenario.create;
import static com.tostao.tostao_api.transaction.PendingScenario.edit;
import static com.tostao.tostao_api.transaction.PendingScenario.transferBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.tostao.tostao_api.support.AuthSupport;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/** FR-015 e SC-003: as pontas de uma transferência ficam sempre no mesmo estado, mesmo com operações simultâneas reais. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PendingConcurrencyTest {

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
			answers.add(future.get(60, TimeUnit.SECONDS));
		}
		pool.shutdown();
		return answers;
	}

	private static Answer answer(ResultActions result) throws Exception {
		var response = result.andReturn().getResponse();
		return new Answer(response.getStatus(), response.getContentAsString());
	}

	private List<String> legStatuses(UUID workspaceId) {
		return owner.client().sql("select status from transactions where workspace_id = :w and type = 'TRANSFER' order by id")
				.param("w", workspaceId).query(String.class).list();
	}

	@Test
	void twentySimultaneousClearsOfTheSameTransferLeaveBothLegsClearedOnce() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID leg = UUID.fromString(JsonPath.read(create(mvc, s.owner(), transferBody(s.checking(), s.savings(), 10_000,
				"2026-11-28", "Reserva", "PENDING")).andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString(), "$.id"));
		List<Callable<Answer>> actions = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			actions.add(() -> answer(clear(mvc, s.owner(), leg)));
		}

		List<Answer> answers = together(actions);

		assertThat(answers).extracting(Answer::status).containsOnly(200);
		assertThat(legStatuses(s.owner().workspaceId())).containsExactly("CLEARED", "CLEARED");
		// os saldos mudaram uma só vez: 100000 - 5000 (cenário) - 10000 e 0 + 10000
		String checking = account(mvc, s.owner(), s.checking()).andReturn().getResponse().getContentAsString();
		String savings = account(mvc, s.owner(), s.savings()).andReturn().getResponse().getContentAsString();
		assertThat((Number) JsonPath.read(checking, "$.balanceCents")).isEqualTo(85_000);
		assertThat((Number) JsonPath.read(savings, "$.balanceCents")).isEqualTo(10_000);
	}

	@Test
	void clearingAndDeletingTheSameTransactionAtTheSameTimeNeverLeavesAnInconsistentState() throws Exception {
		WorkspaceFixtures f = fixtures();
		PendingScenario s = PendingScenario.create(f);
		UUID w = s.owner().workspaceId();

		for (int i = 0; i < 30; i++) {
			UUID id = f.insertLedgerTransaction(w, s.checking(), s.owner().userId(), s.food(), "EXPENSE", 100,
					java.time.LocalDate.of(2026, 11, 10), "Corrida " + i, false, "PENDING");
			List<Answer> answers = together(List.of(
					() -> answer(clear(mvc, s.owner(), id)),
					() -> answer(mvc.perform(delete(PendingScenario.transactionsUrl(w) + "/" + id)
							.cookie(AuthSupport.session(s.owner().token()))))));

			int clearStatus = answers.get(0).status();
			String finalStatus = PendingScenario.statusOf(owner, id);
			boolean deleted = owner.client().sql("select deleted_at is not null from transactions where id = :id")
					.param("id", id).query(Boolean.class).single();
			// a exclusão sempre vence no fim; a efetivação ou ocorreu antes (200) ou recebeu "não encontrado" (404)
			assertThat(answers.get(1).status()).as("rodada " + i).isEqualTo(204);
			assertThat(deleted).as("rodada " + i).isTrue();
			assertThat(clearStatus).as("rodada " + i).isIn(200, 404);
			assertThat(finalStatus).as("rodada " + i).isEqualTo(clearStatus == 200 ? "CLEARED" : "PENDING");
		}
	}

	@Test
	void clearingAndEditingTheSameTransferAtTheSameTimeKeepsBothLegsInTheSameStateAndAmount() throws Exception {
		WorkspaceFixtures f = fixtures();
		PendingScenario s = PendingScenario.create(f);
		UUID w = s.owner().workspaceId();

		for (int i = 0; i < 30; i++) {
			UUID leg = UUID.fromString(JsonPath.read(create(mvc, s.owner(), transferBody(s.checking(), s.savings(), 10_000,
					"2026-11-28", "Reserva " + i, "PENDING")).andExpect(status().isCreated()).andReturn().getResponse()
					.getContentAsString(), "$.id"));
			String editBody = "{\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":11000,\"occurredOn\":\"2026-11-28\",\"description\":\"Reserva %d\",\"status\":\"PENDING\"}"
					.formatted(s.checking(), s.savings(), i);

			List<Answer> answers = together(List.of(
					() -> answer(clear(mvc, s.owner(), leg)),
					() -> answer(edit(mvc, s.owner(), leg, editBody))));

			assertThat(answers).extracting(Answer::status).as("rodada " + i).containsOnly(200);
			List<String> states = owner.client().sql("select status from transactions where transfer_group_id = "
					+ "(select transfer_group_id from transactions where id = :id)").param("id", leg).query(String.class).list();
			List<Long> amounts = owner.client().sql("select abs(amount_cents) from transactions where transfer_group_id = "
					+ "(select transfer_group_id from transactions where id = :id)").param("id", leg).query(Long.class).list();
			// as duas pontas sempre no mesmo estado e com o mesmo valor, qualquer que seja a ordem
			assertThat(states).as("rodada " + i).hasSize(2).containsOnly(states.get(0));
			assertThat(amounts).as("rodada " + i).containsOnly(11_000L);
		}
		assertThat(legStatuses(w)).hasSize(60);
	}

	@Test
	void creatingPendingTransactionsInParallelNeverBreaksTheRealizedBalance() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		List<Callable<Answer>> actions = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			int n = i;
			actions.add(() -> answer(create(mvc, s.owner(), body("EXPENSE", s.checking(), 1_000, "2026-11-10", "P" + n,
					s.food(), "PENDING"))));
		}

		List<Answer> answers = together(actions);

		assertThat(answers).extracting(Answer::status).containsOnly(201);
		String json = account(mvc, s.owner(), s.checking()).andReturn().getResponse().getContentAsString();
		assertThat((Number) JsonPath.read(json, "$.balanceCents")).isEqualTo(95_000);
		assertThat((Number) JsonPath.read(json, "$.pendingCents")).isEqualTo(-20_000);
	}

}
