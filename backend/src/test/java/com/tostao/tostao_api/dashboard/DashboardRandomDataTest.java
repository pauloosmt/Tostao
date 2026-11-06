package com.tostao.tostao_api.dashboard;

import static com.tostao.tostao_api.dashboard.DashboardScenario.dashboard;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * SC-001, SC-003, SC-004, SC-005 e SC-013: dados aleatórios com semente fixa, e um cálculo independente
 * escrito aqui, em Java puro sobre as linhas lidas do banco, para cada mês e cada semana. Nada do código do
 * painel (nem {@code DashboardRules}, nem {@code Period}, nem as consultas) é reaproveitado no cálculo.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DashboardRandomDataTest {

	private static final long SEED = 20261107L;
	private static final LocalDate FIRST_DAY = LocalDate.of(2026, 9, 20);
	private static final int DAYS = 85;

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private record Tx(UUID account, UUID category, String type, long amount, LocalDate date, boolean deleted) {
	}

	private record Cat(UUID id, UUID parent, String name) {
	}

	private record Acc(UUID id, String name, long initial, boolean archived) {
	}

	private static long number(String json, String path) {
		return ((Number) JsonPath.read(json, path)).longValue();
	}

	// ---------------------------------------------------------------- dados aleatórios

	private Fixture seed(WorkspaceFixtures fx, Random random) {
		Fixture who = fx.workspaceWithOwner();
		UUID w = who.workspaceId();
		List<UUID> accounts = new ArrayList<>();
		accounts.add(fx.insertAccount(w, "Corrente", "CHECKING", 150_000, false));
		accounts.add(fx.insertAccount(w, "Poupança", "SAVINGS", 0, false));
		accounts.add(fx.insertAccount(w, "Carteira", "CASH", 4_321, false));
		accounts.add(fx.insertAccount(w, "Cartão", "CREDIT_CARD", -80_000, false));
		accounts.add(fx.insertAccount(w, "Antiga", "CHECKING", 9_999, true));

		List<UUID> expense = new ArrayList<>();
		List<UUID> income = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			UUID root = fx.insertCategory(w, null, "Despesa " + i, "EXPENSE", i == 2);
			expense.add(root);
			for (int c = 0; c < i + 1; c++) {
				expense.add(fx.insertCategory(w, root, "Despesa " + i + "." + c, "EXPENSE", c == 1));
			}
		}
		for (int i = 0; i < 2; i++) {
			UUID root = fx.insertCategory(w, null, "Receita " + i, "INCOME", false);
			income.add(root);
			income.add(fx.insertCategory(w, root, "Receita " + i + ".0", "INCOME", i == 1));
		}

		for (int n = 0; n < 420; n++) {
			LocalDate date = FIRST_DAY.plusDays(random.nextInt(DAYS));
			long cents = random.nextInt(10) == 0 ? 1 + random.nextInt(99) : 1 + random.nextInt(300_000);
			boolean deleted = random.nextInt(12) == 0;
			UUID account = accounts.get(random.nextInt(accounts.size()));
			int kind = random.nextInt(100);
			if (kind < 15) {
				UUID to = accounts.get(random.nextInt(accounts.size()));
				while (to.equals(account)) {
					to = accounts.get(random.nextInt(accounts.size()));
				}
				UUID[] legs = fx.insertTransferPair(w, account, to, who.userId(), cents, date, "Transf " + n);
				if (deleted) {
					for (UUID leg : legs) {
						owner.client().sql("update transactions set deleted_at = now() where id = :id")
								.param("id", leg).update();
					}
				}
			} else {
				boolean isIncome = kind >= 75;
				List<UUID> pool = isIncome ? income : expense;
				UUID category = random.nextInt(20) == 0 ? null : pool.get(random.nextInt(pool.size()));
				fx.insertLedgerTransaction(w, account, who.userId(), category, isIncome ? "INCOME" : "EXPENSE", cents,
						date, "Lanç " + n, deleted);
			}
		}
		return who;
	}

	private List<Tx> transactions(UUID w) {
		return owner.client().sql("""
				select account_id, category_id, type, amount_cents, occurred_on, deleted_at is not null as deleted
				from transactions where workspace_id = :w""").param("w", w)
				.query((rs, i) -> new Tx(rs.getObject("account_id", UUID.class), rs.getObject("category_id", UUID.class),
						rs.getString("type"), rs.getLong("amount_cents"), rs.getObject("occurred_on", LocalDate.class),
						rs.getBoolean("deleted")))
				.list();
	}

	private Map<UUID, Cat> categories(UUID w) {
		Map<UUID, Cat> map = new HashMap<>();
		owner.client().sql("select id, parent_id, name from categories where workspace_id = :w").param("w", w)
				.query((rs, i) -> new Cat(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class),
						rs.getString("name")))
				.list().forEach(c -> map.put(c.id(), c));
		return map;
	}

	private List<Acc> accounts(UUID w) {
		return owner.client().sql("""
				select id, name, initial_balance_cents, archived from accounts where workspace_id = :w
				order by lower(name), id""").param("w", w)
				.query((rs, i) -> new Acc(rs.getObject("id", UUID.class), rs.getString("name"),
						rs.getLong("initial_balance_cents"), rs.getBoolean("archived")))
				.list();
	}

	// ---------------------------------------------------------------- cálculo independente

	private static long sum(List<Tx> txs, String type, LocalDate from, LocalDate to) {
		long total = 0;
		for (Tx t : txs) {
			if (!t.deleted() && t.type().equals(type) && !t.date().isBefore(from) && !t.date().isAfter(to)) {
				total += "EXPENSE".equals(type) ? -t.amount() : t.amount();
			}
		}
		return total;
	}

	private static BigDecimal pct(long part, long total) {
		return BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 2,
				RoundingMode.HALF_UP);
	}

	private static BigDecimal bd(Object jsonNumber) {
		return new BigDecimal(String.valueOf(jsonNumber));
	}

	private void checkGroups(String json, String path, String type, List<Tx> txs, Map<UUID, Cat> cats,
			LocalDate from, LocalDate to, long typeTotal) {
		// raiz -> {direto, total, filhos(categoria -> total)}
		Map<UUID, long[]> direct = new LinkedHashMap<>();
		Map<UUID, Map<UUID, Long>> children = new LinkedHashMap<>();
		UUID none = new UUID(0, 0);
		for (Tx t : txs) {
			if (t.deleted() || !t.type().equals(type) || t.date().isBefore(from) || t.date().isAfter(to)) {
				continue;
			}
			long amount = "EXPENSE".equals(type) ? -t.amount() : t.amount();
			UUID root = none;
			UUID child = null;
			if (t.category() != null) {
				Cat c = cats.get(t.category());
				root = c.parent() == null ? c.id() : c.parent();
				child = c.parent() == null ? null : c.id();
			}
			direct.computeIfAbsent(root, k -> new long[1]);
			children.computeIfAbsent(root, k -> new HashMap<>());
			if (child == null) {
				direct.get(root)[0] += amount;
			} else {
				children.get(root).merge(child, amount, Long::sum);
			}
		}
		List<Map<String, Object>> groups = JsonPath.read(json, path);
		assertThat(groups).as(path + " quantidade de grupos").hasSize(direct.size());
		long previousTotal = Long.MAX_VALUE;
		long groupSum = 0;
		for (Map<String, Object> group : groups) {
			Object id = group.get("categoryId");
			UUID root = id == null ? none : UUID.fromString((String) id);
			assertThat(direct).as(path + " grupo " + group.get("name")).containsKey(root);
			long expectedDirect = direct.get(root)[0];
			long expectedChildren = children.get(root).values().stream().mapToLong(Long::longValue).sum();
			long total = ((Number) group.get("totalCents")).longValue();
			assertThat(total).isEqualTo(expectedDirect + expectedChildren);
			assertThat(((Number) group.get("directCents")).longValue()).isEqualTo(expectedDirect);
			assertThat(bd(group.get("percent"))).isEqualByComparingTo(pct(total, typeTotal));
			assertThat(total).as("ordem decrescente").isLessThanOrEqualTo(previousTotal);
			previousTotal = total;
			groupSum += total;
			if (root.equals(none)) {
				assertThat((String) group.get("name")).isEqualTo("Sem categoria");
			}
			List<Map<String, Object>> items = (List<Map<String, Object>>) group.get("children");
			assertThat(items).hasSameSizeAs(children.get(root).keySet());
			long previousChild = Long.MAX_VALUE;
			for (Map<String, Object> item : items) {
				UUID cid = UUID.fromString((String) item.get("categoryId"));
				long childTotal = ((Number) item.get("totalCents")).longValue();
				assertThat(childTotal).isEqualTo(children.get(root).get(cid));
				assertThat(bd(item.get("percent"))).isEqualByComparingTo(pct(childTotal, typeTotal));
				assertThat(childTotal).isLessThanOrEqualTo(previousChild);
				previousChild = childTotal;
			}
		}
		assertThat(groupSum).as(path + " soma dos grupos = total do tipo").isEqualTo(typeTotal);
	}

	/** Compara todos os números do painel de um período com o cálculo independente. */
	private void check(Fixture who, String query, String type, String id, LocalDate start, LocalDate end,
			LocalDate previousStart, LocalDate previousEnd) throws Exception {
		List<Tx> txs = transactions(who.workspaceId());
		Map<UUID, Cat> cats = categories(who.workspaceId());
		List<Acc> accs = accounts(who.workspaceId());
		String json = dashboard(mvc, who, query).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		String label = query + ": ";

		assertThat(JsonPath.<String>read(json, "$.period.type")).as(label).isEqualTo(type);
		assertThat(JsonPath.<String>read(json, "$.period.id")).as(label).isEqualTo(id);
		assertThat(JsonPath.<String>read(json, "$.period.startDate")).isEqualTo(start.toString());
		assertThat(JsonPath.<String>read(json, "$.period.endDate")).isEqualTo(end.toString());

		long income = sum(txs, "INCOME", start, end);
		long expense = sum(txs, "EXPENSE", start, end);
		assertThat(number(json, "$.totals.incomeCents")).as(label + "receitas").isEqualTo(income);
		assertThat(number(json, "$.totals.expenseCents")).as(label + "despesas").isEqualTo(expense);
		assertThat(number(json, "$.totals.resultCents")).as(label + "resultado").isEqualTo(income - expense);

		checkGroups(json, "$.incomeByCategory", "INCOME", txs, cats, start, end, income);
		checkGroups(json, "$.expenseByCategory", "EXPENSE", txs, cats, start, end, expense);

		List<Map<String, Object>> daily = JsonPath.read(json, "$.daily");
		assertThat(daily).hasSize((int) (end.toEpochDay() - start.toEpochDay() + 1));
		long dayIncome = 0;
		long dayExpense = 0;
		for (int i = 0; i < daily.size(); i++) {
			LocalDate day = start.plusDays(i);
			Map<String, Object> point = daily.get(i);
			assertThat(point.get("date")).isEqualTo(day.toString());
			assertThat(((Number) point.get("incomeCents")).longValue()).isEqualTo(sum(txs, "INCOME", day, day));
			assertThat(((Number) point.get("expenseCents")).longValue()).isEqualTo(sum(txs, "EXPENSE", day, day));
			dayIncome += ((Number) point.get("incomeCents")).longValue();
			dayExpense += ((Number) point.get("expenseCents")).longValue();
			long expectedBalance = 0;
			for (Acc a : accs) {
				if (!a.archived()) {
					expectedBalance += a.initial() + balanceMovement(txs, a.id(), null, day);
				}
			}
			assertThat(((Number) point.get("balanceCents")).longValue()).as(label + "saldo do dia " + day)
					.isEqualTo(expectedBalance);
		}
		assertThat(dayIncome).isEqualTo(income);
		assertThat(dayExpense).isEqualTo(expense);

		long previousIncome = sum(txs, "INCOME", previousStart, previousEnd);
		long previousExpense = sum(txs, "EXPENSE", previousStart, previousEnd);
		assertThat(JsonPath.<String>read(json, "$.comparison.previousPeriod.startDate"))
				.isEqualTo(previousStart.toString());
		assertThat(JsonPath.<String>read(json, "$.comparison.previousPeriod.endDate"))
				.isEqualTo(previousEnd.toString());
		checkChange(json, "$.comparison.income", income, previousIncome);
		checkChange(json, "$.comparison.expense", expense, previousExpense);
		checkChange(json, "$.comparison.result", income - expense, previousIncome - previousExpense);

		long openingTotal = 0;
		long closingTotal = 0;
		long currentTotal = 0;
		List<Map<String, Object>> accountJson = JsonPath.read(json, "$.balances.accounts");
		List<Acc> active = accs.stream().filter(a -> !a.archived()).toList();
		assertThat(accountJson).hasSameSizeAs(active);
		for (int i = 0; i < active.size(); i++) {
			Acc a = active.get(i);
			Map<String, Object> got = accountJson.get(i);
			long opening = a.initial() + balanceMovement(txs, a.id(), null, start.minusDays(1));
			long closing = a.initial() + balanceMovement(txs, a.id(), null, end);
			long current = a.initial() + balanceMovement(txs, a.id(), null, null);
			assertThat(got.get("id")).isEqualTo(a.id().toString());
			assertThat(((Number) got.get("openingBalanceCents")).longValue()).as(label + a.name()).isEqualTo(opening);
			assertThat(((Number) got.get("closingBalanceCents")).longValue()).as(label + a.name()).isEqualTo(closing);
			assertThat(((Number) got.get("balanceCents")).longValue()).as(label + a.name()).isEqualTo(current);
			openingTotal += opening;
			closingTotal += closing;
			currentTotal += current;
		}
		assertThat(number(json, "$.balances.openingTotalCents")).isEqualTo(openingTotal);
		assertThat(number(json, "$.balances.closingTotalCents")).isEqualTo(closingTotal);
		assertThat(number(json, "$.balances.totalBalanceCents")).isEqualTo(currentTotal);
		assertThat(((Number) daily.get(daily.size() - 1).get("balanceCents")).longValue()).isEqualTo(closingTotal);

		// o saldo de hoje e o total são idênticos aos do relatório da spec 002
		String report = mvc.perform(get("/api/v1/workspaces/" + who.workspaceId() + "/reports/balance")
				.cookie(session(who.token()))).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		assertThat(number(report, "$.totalBalanceCents")).isEqualTo(currentTotal);
		assertThat(JsonPath.<List<Number>>read(report, "$.accounts[*].balanceCents").stream()
				.map(Number::longValue).toList())
				.isEqualTo(JsonPath.<List<Number>>read(json, "$.balances.accounts[*].balanceCents").stream()
						.map(Number::longValue).toList());
	}

	/** Soma dos lançamentos não excluídos da conta até a data (inclusive); {@code null} = sem limite. */
	private static long balanceMovement(List<Tx> txs, UUID account, LocalDate ignored, LocalDate until) {
		long total = 0;
		for (Tx t : txs) {
			if (!t.deleted() && t.account().equals(account) && (until == null || !t.date().isAfter(until))) {
				total += t.amount();
			}
		}
		return total;
	}

	private static void checkChange(String json, String path, long current, long previous) {
		assertThat(number(json, path + ".previousCents")).as(path).isEqualTo(previous);
		assertThat(number(json, path + ".changeCents")).as(path).isEqualTo(current - previous);
		Object percent = JsonPath.read(json, path + ".changePercent");
		if (previous == 0) {
			assertThat(percent).as(path).isNull();
		} else {
			BigDecimal expected = BigDecimal.valueOf(current - previous).multiply(BigDecimal.valueOf(100))
					.divide(BigDecimal.valueOf(Math.abs(previous)), 2, RoundingMode.HALF_UP);
			assertThat(bd(percent)).as(path).isEqualByComparingTo(expected);
		}
	}

	/** Garante que os dados aleatórios não são fáceis demais: o teste só vale se cobrir os casos difíceis. */
	private void assertTheSeedExercisesTheHardCases(UUID w) {
		List<Tx> txs = transactions(w);
		Map<UUID, Cat> cats = categories(w);
		UUID archivedAccount = accounts(w).stream().filter(Acc::archived).findFirst().orElseThrow().id();
		long uncategorized = txs.stream().filter(t -> !t.deleted() && !t.type().equals("TRANSFER")
				&& t.category() == null).count();
		long children = txs.stream().filter(t -> !t.deleted() && t.category() != null
				&& cats.get(t.category()).parent() != null).count();
		long toArchived = txs.stream().filter(t -> !t.deleted() && t.type().equals("TRANSFER")
				&& t.account().equals(archivedAccount)).count();
		long deleted = txs.stream().filter(Tx::deleted).count();
		long transfers = txs.stream().filter(t -> t.type().equals("TRANSFER")).count();

		assertThat(txs).hasSizeGreaterThan(400);
		assertThat(uncategorized).as("sem categoria").isPositive();
		assertThat(children).as("em subcategoria").isPositive();
		assertThat(toArchived).as("transferência com conta arquivada").isPositive();
		assertThat(deleted).as("excluídos").isPositive();
		assertThat(transfers).as("transferências").isPositive();
	}

	// ---------------------------------------------------------------- os testes

	@Test
	void everyNumberOfEveryMonthMatchesTheIndependentCalculation() throws Exception {
		Fixture who = seed(new WorkspaceFixtures(owner, tokens), new Random(SEED));
		assertTheSeedExercisesTheHardCases(who.workspaceId());

		for (YearMonth month : new YearMonth[] { YearMonth.of(2026, 9), YearMonth.of(2026, 10),
				YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1) }) {
			YearMonth previous = month.minusMonths(1);
			check(who, "month=" + month, "MONTH", month.toString(), month.atDay(1), month.atEndOfMonth(),
					previous.atDay(1), previous.atEndOfMonth());
		}
	}

	@Test
	void everyNumberOfEveryWeekMatchesTheIndependentCalculation() throws Exception {
		Fixture who = seed(new WorkspaceFixtures(owner, tokens), new Random(SEED + 1));

		LocalDate monday = LocalDate.of(2026, 9, 14).with(DayOfWeek.MONDAY);
		for (int i = 0; i < 14; i++) {
			LocalDate start = monday.plusWeeks(i);
			String id = "%d-W%02d".formatted(start.get(IsoFields.WEEK_BASED_YEAR),
					start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
			check(who, "week=" + id, "WEEK", id, start, start.plusDays(6), start.minusDays(7), start.minusDays(1));
		}
	}

}
