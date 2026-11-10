package com.tostao.tostao_api.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

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
import com.tostao.tostao_api.support.AuthSupport;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * SC-001, SC-002, SC-004 e SC-005: lançamentos aleatórios com semente fixa (receitas, despesas, transferências,
 * excluídos, efetivados e pendentes, em contas ativas e arquivada), conferidos contra um cálculo
 * <b>independente</b> feito aqui sobre as linhas semeadas: saldo, pendente e previsto de cada conta, o previsto
 * total, o bloco pendente e os totais do dashboard, e o gasto de um orçamento mensal. Depois, efetivar todos os
 * pendentes e a ida e volta de um sorteio de efetivados para pendente sem resíduo.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PendingRandomDataTest {

	private static final LocalDate NOV_FIRST = LocalDate.of(2026, 11, 1);
	private static final LocalDate NOV_LAST = LocalDate.of(2026, 11, 30);

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	/** Uma linha semeada. {@code signed} traz o sinal: despesa e saída negativas, receita e entrada positivas. */
	private record Tx(UUID id, UUID account, String type, long signed, LocalDate date, boolean pending, boolean deleted,
			UUID category) {
	}

	@Test
	void everyRealizedAndProjectedNumberMatchesAnIndependentCalculationForTwoSeeds() throws Exception {
		run(20261109L);
		run(20261110L);
	}

	private void run(long seed) throws Exception {
		Random random = new Random(seed);
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		Fixture fx = f.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID u = fx.userId();

		Map<UUID, String> names = new HashMap<>();
		Map<UUID, Long> initial = new HashMap<>();
		UUID a1 = account(f, w, "Corrente", 100_000, false, names, initial);
		UUID a2 = account(f, w, "Poupança", 50_000, false, names, initial);
		UUID a3 = account(f, w, "Carteira", 0, false, names, initial);
		UUID old = account(f, w, "Antiga", 20_000, true, names, initial);
		List<UUID> accounts = List.of(a1, a2, a3, old);
		UUID food = f.insertCategory(w, null, "Alimentação", "EXPENSE", false);
		UUID market = f.insertCategory(w, food, "Mercado", "EXPENSE", false);
		UUID transport = f.insertCategory(w, null, "Transporte", "EXPENSE", false);
		UUID salary = f.insertCategory(w, null, "Salário", "INCOME", false);
		List<UUID> expenseCategories = List.of(food, market, transport);
		Map<UUID, UUID> rootOf = Map.of(food, food, market, food, transport, transport);

		List<Tx> txs = new ArrayList<>();
		for (int i = 0; i < 400; i++) {
			LocalDate date = LocalDate.of(2026, 10, 20).plusDays(random.nextInt(50));
			boolean pending = random.nextInt(100) < 35;
			boolean deleted = random.nextInt(100) < 5;
			long cents = 1 + random.nextInt(60_000);
			UUID account = accounts.get(random.nextInt(accounts.size()));
			int roll = random.nextInt(100);
			if (roll < 20) {
				UUID id = f.insertLedgerTransaction(w, account, u, salary, "INCOME", cents, date, "ganho", deleted,
						pending ? "PENDING" : "CLEARED");
				txs.add(new Tx(id, account, "INCOME", cents, date, pending, deleted, null));
			} else if (roll < 35) {
				UUID to = accounts.get(random.nextInt(accounts.size()));
				while (to.equals(account)) {
					to = accounts.get(random.nextInt(accounts.size()));
				}
				UUID[] ids = f.insertTransferPair(w, account, to, u, cents, date, "transferência", pending ? "PENDING" : "CLEARED");
				txs.add(new Tx(ids[0], account, "TRANSFER", -cents, date, pending, false, null));
				txs.add(new Tx(ids[1], to, "TRANSFER", cents, date, pending, false, null));
			} else {
				UUID category = expenseCategories.get(random.nextInt(expenseCategories.size()));
				UUID id = f.insertLedgerTransaction(w, account, u, category, "EXPENSE", cents, date, "gasto", deleted,
						pending ? "PENDING" : "CLEARED");
				txs.add(new Tx(id, account, "EXPENSE", -cents, date, pending, deleted, category));
			}
		}
		f.insertMonthlyBudget(w, NOV_FIRST, food, 10_000_000, u);
		f.insertMonthlyBudget(w, NOV_FIRST, null, 10_000_000, u);
		String label = "semente " + seed;

		// 1) saldo, pendente e previsto de cada conta ativa, e os totais do relatório
		verifyAccounts(fx, txs, accounts.subList(0, 3), names, initial, label);

		// 2) dashboard de novembro: totais realizados e bloco pendente, todos independentes
		verifyDashboardAndBudget(fx, txs, rootOf, food, label);

		// 3) efetivar todos os pendentes: o realizado passa a incluí-los e o previsto de antes não muda
		long projectedBefore = totalProjected(txs, accounts.subList(0, 3), initial);
		List<UUID> pendingIds = txs.stream().filter(t -> t.pending() && !t.deleted()).map(Tx::id).toList();
		for (UUID id : pendingIds) {
			mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
					.post(PendingScenario.transactionsUrl(w) + "/" + id + "/clear").cookie(AuthSupport.session(fx.token())))
					.andExpect(status().isOk());
		}
		List<Tx> allCleared = txs.stream().map(t -> t.pending() && !t.deleted()
				? new Tx(t.id(), t.account(), t.type(), t.signed(), t.date(), false, t.deleted(), t.category()) : t).toList();
		// os pendentes excluídos continuam pendentes e fora de tudo
		verifyAccounts(fx, allCleared, accounts.subList(0, 3), names, initial, label + " depois de efetivar");
		String report = body(get("/api/v1/workspaces/" + w + "/reports/balance"), fx);
		assertThat(((Number) JsonPath.read(report, "$.totalBalanceCents")).longValue()).as(label + " previsto virou saldo")
				.isEqualTo(projectedBefore);

		// 4) ida e volta: 60 efetivados viram pendentes e voltam, sem resíduo
		String before = body(get("/api/v1/workspaces/" + w + "/accounts"), fx);
		List<UUID> sample = allCleared.stream().filter(t -> !t.deleted() && !t.type().equals("TRANSFER")).map(Tx::id)
				.limit(60).toList();
		owner.client().sql("update transactions set status = 'PENDING' where id in (:ids)").param("ids", sample).update();
		assertThat(body(get("/api/v1/workspaces/" + w + "/accounts"), fx)).as(label + " saiu do realizado").isNotEqualTo(before);
		owner.client().sql("update transactions set status = 'CLEARED' where id in (:ids)").param("ids", sample).update();
		assertThat(body(get("/api/v1/workspaces/" + w + "/accounts"), fx)).as(label + " ida e volta sem resíduo").isEqualTo(before);
	}

	private UUID account(WorkspaceFixtures f, UUID w, String name, long initialCents, boolean archived, Map<UUID, String> names,
			Map<UUID, Long> initial) {
		UUID id = f.insertAccount(w, name, "CHECKING", initialCents, archived);
		names.put(id, name);
		initial.put(id, initialCents);
		return id;
	}

	private String body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, Fixture fx)
			throws Exception {
		return mvc.perform(request.cookie(AuthSupport.session(fx.token()))).andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
	}

	private static long sum(List<Tx> txs, UUID account, boolean pending) {
		return txs.stream().filter(t -> t.account().equals(account) && t.pending() == pending && !t.deleted())
				.mapToLong(Tx::signed).sum();
	}

	private static long totalProjected(List<Tx> txs, List<UUID> active, Map<UUID, Long> initial) {
		long total = 0;
		for (UUID account : active) {
			total += initial.get(account) + sum(txs, account, false) + sum(txs, account, true);
		}
		return total;
	}

	private void verifyAccounts(Fixture fx, List<Tx> txs, List<UUID> active, Map<UUID, String> names,
			Map<UUID, Long> initial, String label) throws Exception {
		String json = body(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts"), fx);
		long totalBalance = 0;
		long totalProjected = 0;
		for (UUID account : active) {
			long balance = initial.get(account) + sum(txs, account, false);
			long pending = sum(txs, account, true);
			String where = label + ", conta " + names.get(account);
			List<?> balances = JsonPath.read(json, "$[?(@.name=='" + names.get(account) + "')].balanceCents");
			List<?> pendings = JsonPath.read(json, "$[?(@.name=='" + names.get(account) + "')].pendingCents");
			List<?> projected = JsonPath.read(json, "$[?(@.name=='" + names.get(account) + "')].projectedBalanceCents");
			assertThat(((Number) balances.get(0)).longValue()).as(where + " saldo").isEqualTo(balance);
			assertThat(((Number) pendings.get(0)).longValue()).as(where + " pendente").isEqualTo(pending);
			assertThat(((Number) projected.get(0)).longValue()).as(where + " previsto").isEqualTo(balance + pending);
			totalBalance += balance;
			totalProjected += balance + pending;
		}
		String report = body(get("/api/v1/workspaces/" + fx.workspaceId() + "/reports/balance"), fx);
		assertThat(((Number) JsonPath.read(report, "$.totalBalanceCents")).longValue()).as(label + " saldo total").isEqualTo(totalBalance);
		assertThat(((Number) JsonPath.read(report, "$.totalProjectedCents")).longValue()).as(label + " previsto total").isEqualTo(totalProjected);
	}

	private void verifyDashboardAndBudget(Fixture fx, List<Tx> txs, Map<UUID, UUID> rootOf, UUID food, String label)
			throws Exception {
		List<Tx> november = txs.stream().filter(t -> !t.deleted() && !t.type().equals("TRANSFER")
				&& !t.date().isBefore(NOV_FIRST) && !t.date().isAfter(NOV_LAST)).toList();
		long income = november.stream().filter(t -> !t.pending() && t.type().equals("INCOME")).mapToLong(Tx::signed).sum();
		long expense = -november.stream().filter(t -> !t.pending() && t.type().equals("EXPENSE")).mapToLong(Tx::signed).sum();
		long pendingIncome = november.stream().filter(t -> t.pending() && t.type().equals("INCOME")).mapToLong(Tx::signed).sum();
		long pendingExpense = -november.stream().filter(t -> t.pending() && t.type().equals("EXPENSE")).mapToLong(Tx::signed).sum();
		long pendingCount = november.stream().filter(Tx::pending).count();

		String dashboard = body(get("/api/v1/workspaces/" + fx.workspaceId() + "/dashboard?month=2026-11"), fx);
		assertThat(((Number) JsonPath.read(dashboard, "$.totals.incomeCents")).longValue()).as(label + " receita").isEqualTo(income);
		assertThat(((Number) JsonPath.read(dashboard, "$.totals.expenseCents")).longValue()).as(label + " despesa").isEqualTo(expense);
		assertThat(((Number) JsonPath.read(dashboard, "$.pending.incomeCents")).longValue()).as(label + " receita pendente").isEqualTo(pendingIncome);
		assertThat(((Number) JsonPath.read(dashboard, "$.pending.expenseCents")).longValue()).as(label + " despesa pendente").isEqualTo(pendingExpense);
		assertThat(((Number) JsonPath.read(dashboard, "$.pending.count")).longValue()).as(label + " quantidade pendente").isEqualTo(pendingCount);

		long foodSpent = -november.stream().filter(t -> !t.pending() && t.type().equals("EXPENSE") && food.equals(rootOf.get(t.category())))
				.mapToLong(Tx::signed).sum();
		String budget = body(get("/api/v1/workspaces/" + fx.workspaceId() + "/monthly-budgets?month=2026-11"), fx);
		assertThat(((Number) JsonPath.read(budget, "$.totalSpentCents")).longValue()).as(label + " gasto total do orçamento").isEqualTo(expense);
		List<?> foodItem = JsonPath.read(budget, "$.budgets[?(@.categoryId=='" + food + "')].spentCents");
		assertThat(((Number) foodItem.get(0)).longValue()).as(label + " gasto de Alimentação").isEqualTo(foodSpent);
	}

}
