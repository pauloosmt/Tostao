package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.BudgetScenario.body;
import static com.tostao.tostao_api.budget.BudgetScenario.copy;
import static com.tostao.tostao_api.budget.BudgetScenario.define;
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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.workspace.TenantSession;

/** SC-005, SC-006 e SC-007 sob gravações simultâneas reais (threads e conexões distintas). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BudgetConcurrencyTest {

	@Autowired
	MockMvc mvc;
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

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private record Answer(int status, String body) {
	}

	/** Roda as ações ao mesmo tempo (largada sincronizada) e devolve as respostas na ordem. */
	private static List<Answer> together(List<java.util.concurrent.Callable<Answer>> actions) throws Exception {
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

	private static Answer answer(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		var response = result.andReturn().getResponse();
		return new Answer(response.getStatus(), response.getContentAsString());
	}

	private void sameScopeAtTheSameTime(BudgetScenario s, UUID category) throws Exception {
		UUID w = s.owner().workspaceId();
		List<java.util.concurrent.Callable<Answer>> actions = new ArrayList<>();
		for (int i = 1; i <= 20; i++) {
			long amount = i * 1_000L;
			actions.add(() -> answer(define(mvc, s.owner(), body("2026-W45", category, amount))));
		}

		List<Answer> answers = together(actions);

		assertThat(answers).extracting(Answer::status).containsOnly(200, 201);
		assertThat(answers.stream().filter(a -> a.status() == 201).count()).isEqualTo(1);
		var rows = owner.client().sql("""
				select amount_cents, created_by, created_at from weekly_budgets
				where workspace_id = :w and week_start = :s and category_id is not distinct from cast(:c as uuid)""")
				.param("w", w).param("s", BudgetScenario.W45).param("c", category)
				.query((rs, i) -> rs.getLong("amount_cents")).list();
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).isBetween(1_000L, 20_000L);
		assertThat(rows.get(0) % 1_000).isZero();
		// o limite criado primeiro mantém o criador e a data: todas as respostas apontam o mesmo id
		assertThat(answers.stream().map(a -> (String) JsonPath.read(a.body(), "$.id")).distinct().count()).isEqualTo(1);
		assertThat(answers.stream().map(a -> (String) JsonPath.read(a.body(), "$.createdAt")).distinct().count())
				.isEqualTo(1);
	}

	@Test
	void twentySimultaneousDefinitionsOfTheSameCategoryLimitLeaveOneLimit() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		sameScopeAtTheSameTime(s, s.food());
	}

	@Test
	void twentySimultaneousDefinitionsOfTheGeneralLimitLeaveOneLimit() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());

		sameScopeAtTheSameTime(s, null);
	}

	@Test
	void aDefinitionWaitsForAnArchivingInProgressAndThenIsRefused() throws Exception {
		BudgetScenario s = BudgetScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		CountDownLatch archived = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		// outra pessoa arquiva a categoria numa transação que demora a confirmar
		Future<?> archiver = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			tenant.enterWorkspace(s.owner().userId(), w);
			appJdbc.sql("update categories set archived = true where id = :id and workspace_id = :w")
					.param("id", s.food()).param("w", w).update();
			archived.countDown();
			try {
				Thread.sleep(800);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}));
		archived.await(30, TimeUnit.SECONDS);

		long start = System.nanoTime();
		Answer result = answer(define(mvc, s.owner(), body("2026-W45", s.food(), 100)));
		long millis = (System.nanoTime() - start) / 1_000_000;
		archiver.get(30, TimeUnit.SECONDS);
		pool.shutdown();

		assertThat(result.status()).isEqualTo(409);
		assertThat(millis).as("a definição esperou o arquivamento confirmar").isGreaterThan(300);
		assertThat(fixtures().countWeeklyBudgets(w)).isZero();
	}

	@Test
	void definingAndArchivingTheSameCategoryAtTheSameTimeNeverLeavesAnInconsistentState() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();

		for (int i = 0; i < 30; i++) {
			UUID category = fixtures.insertCategory(w, null, "Corrida " + i, "EXPENSE", false);
			LocalDate week = BudgetScenario.W45.plusWeeks(i);
			String weekId = com.tostao.tostao_api.dashboard.Period.containing(week).id();
			List<Answer> answers = together(List.of(
					() -> answer(define(mvc, s.owner(), body(weekId, category, 500))),
					() -> {
						new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
							tenant.enterWorkspace(s.owner().userId(), w);
							appJdbc.sql("update categories set archived = true where id = :id and workspace_id = :w")
									.param("id", category).param("w", w).update();
						});
						return new Answer(0, "");
					}));

			int defineStatus = answers.get(0).status();
			long rows = owner.client().sql("select count(*) from weekly_budgets where category_id = :c")
					.param("c", category).query(Long.class).single();
			// ou o limite nasceu antes do arquivamento (e fica), ou a definição foi recusada e nada nasceu
			assertThat(defineStatus).isIn(201, 409);
			assertThat(rows).as("rodada " + i).isEqualTo(defineStatus == 201 ? 1 : 0);
		}
	}

	@Test
	void aCopyAndADefinitionOfTheSameScopeAtTheSameTimeKeepOneLimitPerScopeAndWeek() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		UUID w = s.owner().workspaceId();

		for (int i = 0; i < 20; i++) {
			LocalDate source = LocalDate.of(2027, 1, 4).plusWeeks(2L * i);
			LocalDate target = source.plusWeeks(1);
			String sourceId = com.tostao.tostao_api.dashboard.Period.containing(source).id();
			String targetId = com.tostao.tostao_api.dashboard.Period.containing(target).id();
			fixtures.insertWeeklyBudget(w, source, s.food(), 1_000, s.owner().userId());
			fixtures.insertWeeklyBudget(w, source, null, 2_000, s.owner().userId());

			List<Answer> answers = together(List.of(
					() -> answer(copy(mvc, w, s.owner(), "{\"fromWeek\":\"" + sourceId + "\",\"weeks\":1}")),
					() -> answer(define(mvc, s.owner(), body(targetId, null, 9_999)))));

			// a cópia responde sempre 200; a definição responde 201 se chegou antes, 200 se a cópia gravou o geral antes
			assertThat(answers.get(0).status()).isEqualTo(200);
			assertThat(answers.get(1).status()).isIn(200, 201);
			long rows = owner.client().sql("select count(*) from weekly_budgets where workspace_id = :w and week_start = :s")
					.param("w", w).param("s", target).query(Long.class).single();
			assertThat(rows).as("rodada " + i).isEqualTo(2);
			int created = JsonPath.read(answers.get(0).body(), "$.created");
			int skipped = JsonPath.read(answers.get(0).body(), "$.skippedExisting");
			assertThat(created + skipped).isEqualTo(2);
		}
	}

	@Test
	void twoIdenticalCopiesAtTheSameTimeNeverDuplicateAndTheirCountsAddUp() throws Exception {
		WorkspaceFixtures fixtures = fixtures();
		BudgetScenario s = BudgetScenario.create(fixtures);
		s.seedQuickstartBudgets(fixtures);
		UUID w = s.owner().workspaceId();

		List<Answer> answers = together(List.of(
				() -> answer(copy(mvc, w, s.owner(), "{\"fromWeek\":\"2026-W45\",\"weeks\":10}")),
				() -> answer(copy(mvc, w, s.owner(), "{\"fromWeek\":\"2026-W45\",\"weeks\":10}"))));

		assertThat(answers).extracting(Answer::status).containsOnly(200);
		int created = answers.stream().mapToInt(a -> JsonPath.<Integer>read(a.body(), "$.created")).sum();
		int skipped = answers.stream().mapToInt(a -> JsonPath.<Integer>read(a.body(), "$.skippedExisting")).sum();
		assertThat(created).isEqualTo(40);
		assertThat(skipped).isEqualTo(40);
		assertThat(fixtures.countWeeklyBudgets(w)).isEqualTo(4 + 40);
		assertThat(owner.client().sql("""
				select count(*) from (select 1 from weekly_budgets where workspace_id = :w
				group by week_start, category_id having count(*) > 1) d""").param("w", w).query(Long.class).single())
				.isZero();
	}

}
