package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.month;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.dashboard.Period;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/**
 * FR-021: todos os números do acompanhamento de um mês vêm de uma única leitura consistente. O serviço lê os
 * limites e os gastos uma vez cada e deriva tudo dessas duas listas em memória, então as identidades abaixo
 * valem por construção. O teste prova que elas se mantêm enquanto outra pessoa grava e exclui lançamentos sem
 * parar, e a reflexão trava a anotação {@code REPEATABLE READ}. Como na 006, o isolamento fica guardado pela
 * reflexão, não pelo comportamento: trocá-lo não quebraria as identidades.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MonthlyBudgetConsistencyTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	@Test
	void theReadIsASingleSnapshotAndTheWritesAreScopedAndWritable() throws Exception {
		Method month = MonthlyBudgetService.class.getMethod("month", UUID.class, Period.class);

		Transactional tx = month.getAnnotation(Transactional.class);
		assertThat(tx.readOnly()).isTrue();
		assertThat(tx.isolation()).isEqualTo(Isolation.REPEATABLE_READ);
		assertThat(month.getAnnotation(WorkspaceScoped.class).write()).isFalse();

		for (String name : List.of("define", "update", "delete", "copy")) {
			Method method = Arrays.stream(MonthlyBudgetService.class.getMethods())
					.filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
			assertThat(method.getAnnotation(WorkspaceScoped.class).write()).as(name).isTrue();
			Transactional writeTx = method.getAnnotation(Transactional.class);
			assertThat(writeTx).as(name).isNotNull();
			assertThat(writeTx.readOnly()).as(name).isFalse();
		}
	}

	private static long number(Object value) {
		return ((Number) value).longValue();
	}

	/** Todas as identidades que uma resposta consistente precisa cumprir. */
	private static void assertConsistent(String json) {
		long total = number(JsonPath.read(json, "$.totalSpentCents"));
		long outside = number(JsonPath.read(json, "$.spentOutsideCategoryLimitsCents"));
		List<Map<String, Object>> items = JsonPath.read(json, "$.budgets");
		long general = -1;
		long rootsSum = 0;
		for (Map<String, Object> item : items) {
			long spent = number(item.get("spentCents"));
			long amount = number(item.get("amountCents"));
			assertThat(number(item.get("remainingCents"))).isEqualTo(amount - spent);
			assertThat(item.get("status")).isEqualTo(BudgetRules.status(spent, amount).name());
			if (item.get("categoryName") == null) {
				general = spent;
			} else {
				rootsSum += spent;
			}
		}
		assertThat(general).as("o limite geral soma todas as despesas do mês").isEqualTo(total);
		// as três categorias principais com limite não se sobrepõem: o total é a soma delas mais o resto
		assertThat(rootsSum + outside).as("soma das categorias + fora dos limites").isEqualTo(total);
	}

	@Test
	void everyResponseStaysConsistentWhileAnotherPersonWrites() throws Exception {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		MonthlyBudgetScenario s = MonthlyBudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		UUID u = s.owner().userId();
		UUID other = fixtures.insertCategory(w, null, "Outros", "EXPENSE", false);
		fixtures.insertMonthlyBudget(w, MonthlyBudgetScenario.NOV, null, 400_000, u);
		fixtures.insertMonthlyBudget(w, MonthlyBudgetScenario.NOV, s.food(), 64_000, u);
		fixtures.insertMonthlyBudget(w, MonthlyBudgetScenario.NOV, s.transport(), 32_000, u);
		fixtures.insertMonthlyBudget(w, MonthlyBudgetScenario.NOV, s.leisure(), 12_000, u);
		AtomicBoolean stop = new AtomicBoolean(false);
		AtomicInteger writes = new AtomicInteger();
		ExecutorService pool = Executors.newSingleThreadExecutor();

		Future<?> writer = pool.submit(() -> {
			Random random = new Random(20261110L);
			List<UUID> ids = new ArrayList<>();
			UUID[] categories = { s.food(), s.market(), s.transport(), s.leisure(), other, null };
			while (!stop.get()) {
				if (random.nextInt(10) < 7 || ids.isEmpty()) {
					LocalDate day = MonthlyBudgetScenario.NOV.plusDays(random.nextInt(30));
					ids.add(fixtures.insertLedgerTransaction(w, s.checking(), u, categories[random.nextInt(categories.length)],
							"EXPENSE", 1 + random.nextInt(4_000), day, "Gasto", false));
				} else {
					UUID id = ids.remove(random.nextInt(ids.size()));
					owner.client().sql("update transactions set deleted_at = now() where id = :id").param("id", id)
							.update();
				}
				writes.incrementAndGet();
			}
		});

		try {
			for (int i = 0; i < 150; i++) {
				String json = month(mvc, s.owner(), "month=2026-11").andExpect(status().isOk()).andReturn().getResponse()
						.getContentAsString();
				assertConsistent(json);
			}
		} finally {
			stop.set(true);
			writer.get(30, TimeUnit.SECONDS);
			pool.shutdown();
		}

		assertThat(writes.get()).as("o escritor chegou a gravar durante as leituras").isGreaterThan(20);
		assertConsistent(month(mvc, s.owner(), "month=2026-11").andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString());
	}

}
