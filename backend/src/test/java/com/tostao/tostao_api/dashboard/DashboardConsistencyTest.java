package com.tostao.tostao_api.dashboard;

import static com.tostao.tostao_api.dashboard.DashboardScenario.dashboard;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/**
 * SC-009 e FR-013: com outra pessoa gravando e excluindo lançamentos sem parar, o painel nunca devolve
 * números que se contradigam. Os totais vêm de uma consulta e os grupos e os saldos de outras: só o
 * snapshot único ({@code REPEATABLE READ}) mantém as identidades.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DashboardConsistencyTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private static long number(String json, String path) {
		return ((Number) JsonPath.read(json, path)).longValue();
	}

	private static long sum(String json, String path) {
		return JsonPath.<List<Number>>read(json, path).stream().mapToLong(Number::longValue).sum();
	}

	/** Todas as identidades que uma resposta consistente precisa cumprir. */
	private static void assertConsistent(String json) {
		long income = number(json, "$.totals.incomeCents");
		long expense = number(json, "$.totals.expenseCents");
		assertThat(number(json, "$.totals.resultCents")).isEqualTo(income - expense);
		assertThat(sum(json, "$.incomeByCategory[*].totalCents")).as("soma dos grupos de receita").isEqualTo(income);
		assertThat(sum(json, "$.expenseByCategory[*].totalCents")).as("soma dos grupos de despesa").isEqualTo(expense);
		assertThat(sum(json, "$.daily[*].incomeCents")).as("soma dos dias de receita").isEqualTo(income);
		assertThat(sum(json, "$.daily[*].expenseCents")).as("soma dos dias de despesa").isEqualTo(expense);
		List<Number> daily = JsonPath.read(json, "$.daily[*].balanceCents");
		assertThat(daily.get(daily.size() - 1).longValue()).as("último saldo diário = fechamento")
				.isEqualTo(number(json, "$.balances.closingTotalCents"));
		assertThat(sum(json, "$.balances.accounts[*].closingBalanceCents")).isEqualTo(
				number(json, "$.balances.closingTotalCents"));
		assertThat(sum(json, "$.balances.accounts[*].openingBalanceCents")).isEqualTo(
				number(json, "$.balances.openingTotalCents"));
		assertThat(sum(json, "$.balances.accounts[*].balanceCents")).isEqualTo(
				number(json, "$.balances.totalBalanceCents"));
	}

	@Test
	void everyResponseStaysConsistentWhileAnotherPersonWrites() throws Exception {
		WorkspaceFixtures fx = new WorkspaceFixtures(owner, tokens);
		DashboardScenario s = DashboardScenario.create(fx);
		UUID w = s.owner().workspaceId();
		AtomicBoolean stop = new AtomicBoolean(false);
		AtomicInteger writes = new AtomicInteger();
		ExecutorService pool = Executors.newSingleThreadExecutor();

		Future<?> writer = pool.submit(() -> {
			Random random = new Random(20261108L);
			List<UUID> ids = new java.util.ArrayList<>();
			while (!stop.get()) {
				LocalDate day = LocalDate.of(2026, 11, 2).plusDays(random.nextInt(7));
				int action = random.nextInt(10);
				if (action < 4) {
					ids.add(fx.insertLedgerTransaction(w, s.checking(), s.owner().userId(), s.food(), "EXPENSE",
							1 + random.nextInt(5_000), day, "Gasto", false));
				} else if (action < 6) {
					ids.add(fx.insertLedgerTransaction(w, s.checking(), s.owner().userId(), s.salary(), "INCOME",
							1 + random.nextInt(9_000), day, "Ganho", false));
				} else if (action < 8) {
					fx.insertTransferPair(w, s.checking(), s.savings(), s.owner().userId(), 1 + random.nextInt(2_000),
							day, "Reserva");
				} else if (!ids.isEmpty()) {
					UUID id = ids.remove(random.nextInt(ids.size()));
					owner.client().sql("update transactions set deleted_at = now() where id = :id").param("id", id)
							.update();
				}
				writes.incrementAndGet();
			}
		});

		try {
			for (int i = 0; i < 150; i++) {
				String query = i % 2 == 0 ? "month=2026-11" : "week=2026-W45";
				String json = dashboard(mvc, s.owner(), query).andExpect(status().isOk()).andReturn().getResponse()
						.getContentAsString();
				assertConsistent(json);
			}
		} finally {
			stop.set(true);
			writer.get(30, TimeUnit.SECONDS);
			pool.shutdown();
		}

		assertThat(writes.get()).as("o escritor chegou a gravar durante as leituras").isGreaterThan(20);
		// e, parado o escritor, a resposta final continua consistente
		assertConsistent(dashboard(mvc, s.owner(), "month=2026-11").andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString());
	}

}
