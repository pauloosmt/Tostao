package com.tostao.tostao_api.budget;

import static com.tostao.tostao_api.budget.MonthlyBudgetScenario.month;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 * SC-001, SC-002, SC-003 e SC-004: dados aleatórios com semente fixa e um cálculo independente, escrito aqui
 * em Java puro sobre as linhas lidas do banco, para cada limite de cada mês. Nada de {@code BudgetRules},
 * das consultas nem da conversão de mês do serviço é reaproveitado no cálculo.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MonthlyBudgetRandomDataTest {

	private static final long SEED = 20261108L;
	/** Primeiro mês testado (2026-12); os 8 meses seguintes incluem a virada do ano e fevereiro de 2027, de 28 dias. */
	private static final LocalDate FIRST_MONTH = LocalDate.of(2026, 12, 1);
	private static final int MONTHS = 8;

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private record Spend(UUID category, long amount, LocalDate date) {
	}

	private record Limit(UUID id, LocalDate monthStart, UUID category, String name, long amount) {
	}

	private record Expected(long spent, BigDecimal percent, String status) {
	}

	@Test
	void everyLimitOfEveryMonthMatchesAnIndependentCalculation() throws Exception {
		check(SEED);
	}

	@Test
	void anotherSeedMatchesToo() throws Exception {
		check(SEED + 1);
	}

	private void check(long seed) throws Exception {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		Random random = new Random(seed);
		seed(fixtures, fx, random);

		// ---- leitura independente do que foi gravado ----
		Map<UUID, UUID> parentOf = new HashMap<>();
		Map<UUID, String> nameOf = new HashMap<>();
		owner.client().sql("select id, parent_id, name from categories where workspace_id = :w").param("w", w)
				.query((rs, i) -> {
					parentOf.put(rs.getObject("id", UUID.class), rs.getObject("parent_id", UUID.class));
					nameOf.put(rs.getObject("id", UUID.class), rs.getString("name"));
					return 0;
				}).list();
		List<Spend> spends = owner.client().sql("""
				select category_id, -amount_cents as cents, occurred_on from transactions
				where workspace_id = :w and type = 'EXPENSE' and deleted_at is null""").param("w", w)
				.query((rs, i) -> new Spend(rs.getObject("category_id", UUID.class), rs.getLong("cents"),
						rs.getObject("occurred_on", LocalDate.class)))
				.list();
		List<Limit> limits = owner.client().sql("""
				select id, month_start, category_id, amount_cents from monthly_budgets where workspace_id = :w""")
				.param("w", w).query((rs, i) -> {
					UUID category = rs.getObject("category_id", UUID.class);
					return new Limit(rs.getObject("id", UUID.class), rs.getObject("month_start", LocalDate.class),
							category, category == null ? null : nameOf.get(category), rs.getLong("amount_cents"));
				}).list();

		int zeroWithSpend = 0, zeroWithout = 0, at80 = 0, at100 = 0, over = 0, bothLevels = 0;
		long compared = 0;
		for (int i = 0; i < MONTHS; i++) {
			LocalDate first = FIRST_MONTH.plusMonths(i);
			LocalDate last = first.withDayOfMonth(first.lengthOfMonth());
			List<Spend> inMonth = spends.stream().filter(s -> !s.date().isBefore(first) && !s.date().isAfter(last))
					.toList();
			List<Limit> monthLimits = limits.stream().filter(l -> l.monthStart().equals(first)).toList();

			long total = inMonth.stream().mapToLong(Spend::amount).sum();
			long outside = inMonth.stream().filter(s -> monthLimits.stream().noneMatch(l -> l.category() != null
					&& (l.category().equals(s.category()) || l.category().equals(parentOf.get(s.category())))))
					.mapToLong(Spend::amount).sum();

			Map<UUID, Expected> expected = new HashMap<>();
			for (Limit l : monthLimits) {
				long spent = inMonth.stream().filter(s -> l.category() == null || l.category().equals(s.category())
						|| l.category().equals(parentOf.get(s.category()))).mapToLong(Spend::amount).sum();
				BigDecimal percent = l.amount() == 0 ? null : BigDecimal.valueOf(spent).multiply(BigDecimal.valueOf(100))
						.divide(BigDecimal.valueOf(l.amount()), 2, RoundingMode.HALF_UP);
				String statusName;
				if (l.amount() == 0) {
					statusName = spent == 0 ? "WITHIN" : "EXCEEDED";
				} else if (BigDecimal.valueOf(spent).compareTo(BigDecimal.valueOf(l.amount()).multiply(new BigDecimal("0.8"))) < 0) {
					statusName = "WITHIN";
				} else if (spent <= l.amount()) {
					statusName = "WARNING";
				} else {
					statusName = "EXCEEDED";
				}
				expected.put(l.id(), new Expected(spent, percent, statusName));

				if (l.amount() == 0 && spent > 0) zeroWithSpend++;
				if (l.amount() == 0 && spent == 0) zeroWithout++;
				if (l.amount() > 0 && spent * 100 == l.amount() * 80) at80++;
				if (l.amount() > 0 && spent == l.amount()) at100++;
				if (l.amount() > 0 && spent == l.amount() + 1) over++;
			}
			bothLevels += monthLimits.stream().filter(l -> l.category() != null && parentOf.get(l.category()) != null
					&& monthLimits.stream().anyMatch(p -> parentOf.get(l.category()).equals(p.category()))).count();

			String id = java.time.YearMonth.from(first).toString();
			String json = month(mvc, fx, "month=" + id).andExpect(status().isOk()).andReturn().getResponse()
					.getContentAsString();

			assertThat(((Number) JsonPath.read(json, "$.totalSpentCents")).longValue()).as(id + " total").isEqualTo(total);
			assertThat(((Number) JsonPath.read(json, "$.spentOutsideCategoryLimitsCents")).longValue())
					.as(id + " fora dos limites").isEqualTo(outside);
			List<Map<String, Object>> items = JsonPath.read(json, "$.budgets");
			assertThat(items).as(id + " quantidade de limites").hasSize(monthLimits.size());
			for (Map<String, Object> item : items) {
				UUID limitId = UUID.fromString((String) item.get("id"));
				Expected e = expected.get(limitId);
				Limit l = monthLimits.stream().filter(x -> x.id().equals(limitId)).findFirst().orElseThrow();
				String label = id + " " + (l.name() == null ? "geral" : l.name());
				assertThat(((Number) item.get("spentCents")).longValue()).as(label + " gasto").isEqualTo(e.spent());
				assertThat(((Number) item.get("amountCents")).longValue()).as(label + " limite").isEqualTo(l.amount());
				assertThat(((Number) item.get("remainingCents")).longValue()).as(label + " restante")
						.isEqualTo(l.amount() - e.spent());
				assertThat(item.get("status")).as(label + " estado").isEqualTo(e.status());
				if (e.percent() == null) {
					assertThat(item.get("percentUsed")).as(label + " percentual").isNull();
				} else {
					assertThat(BigDecimal.valueOf(((Number) item.get("percentUsed")).doubleValue()).setScale(2,
							RoundingMode.HALF_UP)).as(label + " percentual").isEqualByComparingTo(e.percent());
				}
				compared++;
			}

			// ordem esperada, escrita aqui: estado, maior gasto, nome (o geral antes), id
			Comparator<Limit> order = Comparator
					.comparing((Limit l) -> rank(expected.get(l.id()).status()))
					.thenComparing(l -> -expected.get(l.id()).spent())
					.thenComparing(l -> l.name() == null ? "" : l.name().toLowerCase(Locale.ROOT))
					.thenComparing(Limit::id);
			List<String> expectedOrder = monthLimits.stream().sorted(order).map(l -> l.id().toString()).toList();
			List<String> actualOrder = new ArrayList<>();
			items.forEach(item -> actualOrder.add((String) item.get("id")));
			assertThat(actualOrder).as(id + " ordem").isEqualTo(expectedOrder);
		}

		// a semente tem os casos difíceis, senão o teste não prova nada
		assertThat(compared).as("limites comparados").isGreaterThan(30);
		assertThat(zeroWithSpend).as("limite zero com gasto").isPositive();
		assertThat(zeroWithout).as("limite zero sem gasto").isPositive();
		assertThat(at80).as("gasto exatamente em 80%").isPositive();
		assertThat(at100).as("gasto exatamente em 100%").isPositive();
		assertThat(over).as("gasto de 100,01%").isPositive();
		assertThat(bothLevels).as("subcategoria e principal com limite no mesmo mês").isPositive();
		assertThat(spends.stream().anyMatch(s -> s.category() == null)).as("despesa sem categoria").isTrue();
		assertThat(owner.client().sql("select count(*) from transactions where workspace_id = :w and type = 'TRANSFER'")
				.param("w", w).query(Long.class).single()).as("transferências").isPositive();
		assertThat(owner.client().sql("select count(*) from transactions where workspace_id = :w and type = 'INCOME'")
				.param("w", w).query(Long.class).single()).as("receitas").isPositive();
		assertThat(owner.client().sql("select count(*) from transactions where workspace_id = :w and deleted_at is not null")
				.param("w", w).query(Long.class).single()).as("excluídos").isPositive();
		assertThat(owner.client().sql("""
				select count(*) from monthly_budgets b join categories c on c.id = b.category_id
				where b.workspace_id = :w and c.archived""").param("w", w).query(Long.class).single())
				.as("limite em categoria arquivada").isPositive();
	}

	private static int rank(String status) {
		return switch (status) {
			case "EXCEEDED" -> 0;
			case "WARNING" -> 1;
			default -> 2;
		};
	}

	/** Semeia categorias, lançamentos aleatórios, casos plantados e limites. */
	private void seed(WorkspaceFixtures f, Fixture fx, Random random) {
		UUID w = fx.workspaceId();
		UUID u = fx.userId();
		UUID active = f.insertAccount(w, "Corrente", "CHECKING", 0, false);
		UUID oldAccount = f.insertAccount(w, "Antiga", "CASH", 0, true);
		UUID income = f.insertCategory(w, null, "Salário", "INCOME", false);

		List<UUID> expenseCategories = new ArrayList<>();
		UUID r0 = f.insertCategory(w, null, "Alimentação", "EXPENSE", false);
		UUID r0a = f.insertCategory(w, r0, "Mercado", "EXPENSE", false);
		UUID r0b = f.insertCategory(w, r0, "Restaurante", "EXPENSE", false);
		UUID r1 = f.insertCategory(w, null, "Moradia", "EXPENSE", false);
		UUID r1a = f.insertCategory(w, r1, "Aluguel", "EXPENSE", false);
		UUID r2 = f.insertCategory(w, null, "Transporte", "EXPENSE", false);
		UUID r2a = f.insertCategory(w, r2, "Ônibus", "EXPENSE", false);
		UUID r2b = f.insertCategory(w, r2, "Combustível", "EXPENSE", false);
		UUID r2c = f.insertCategory(w, r2, "Aplicativo", "EXPENSE", true);
		UUID r3 = f.insertCategory(w, null, "Antiga", "EXPENSE", true);
		UUID r3a = f.insertCategory(w, r3, "Antiguinha", "EXPENSE", true);
		expenseCategories.addAll(List.of(r0, r0a, r0b, r1, r1a, r2, r2a, r2b, r2c, r3, r3a));

		LocalDate from = FIRST_MONTH.minusDays(4);
		int days = MONTHS * 31 + 8;
		for (int i = 0; i < 800; i++) {
			LocalDate date = from.plusDays(random.nextInt(days));
			UUID account = random.nextInt(6) == 0 ? oldAccount : active;
			int roll = random.nextInt(100);
			long cents = switch (random.nextInt(4)) {
				case 0 -> 1 + random.nextInt(50);
				case 1 -> 10 * (1 + random.nextInt(30));
				default -> 1 + random.nextInt(30_000);
			};
			if (roll < 12) {
				f.insertLedgerTransaction(w, active, u, income, "INCOME", cents, date, "ganho", false);
			} else if (roll < 22) {
				f.insertTransferPair(w, active, oldAccount, u, cents, date, "transferência");
			} else {
				UUID category = random.nextInt(100) < 6 ? null : expenseCategories.get(random.nextInt(expenseCategories.size()));
				f.insertLedgerTransaction(w, account, u, category, "EXPENSE", cents, date, "gasto",
						random.nextInt(100) < 8);
			}
		}

		// casos plantados, em categorias próprias para a conta fechar sem depender do acaso
		LocalDate day = FIRST_MONTH.plusMonths(2).plusDays(10);
		plant(f, w, u, active, "P79", false, 10_000, day, 7_999);
		plant(f, w, u, active, "P80", false, 10_000, day, 5_000, 3_000);
		plant(f, w, u, active, "P100", false, 10_000, day, 10_000);
		plant(f, w, u, active, "P10001", false, 10_000, day, 10_001);
		plant(f, w, u, active, "PZ", false, 0, day, 5);
		plant(f, w, u, active, "PZ2", false, 0, day);
		plant(f, w, u, active, "PARC", true, 5_000, day, 6_000);
		// borda do mês: 28/02/2027 (último dia de fevereiro) conta em fevereiro; 01/03 e 31/01 não
		UUID edge = f.insertCategory(w, null, "PBORDA", "EXPENSE", false);
		f.insertMonthlyBudget(w, FIRST_MONTH.plusMonths(2), edge, 1_000, u);
		for (String date : new String[] { "2027-01-31", "2027-02-01", "2027-02-28", "2027-03-01" }) {
			f.insertLedgerTransaction(w, active, u, edge, "EXPENSE", 100, LocalDate.parse(date), "borda " + date, false);
		}
		// principal e subcategoria com limite no mesmo mês (2027-03)
		LocalDate march = FIRST_MONTH.plusMonths(3);
		f.insertMonthlyBudget(w, march, r0, 20_000, u);
		f.insertMonthlyBudget(w, march, r0a, 5_000, u);

		// limites aleatórios por mês
		for (int i = 0; i < MONTHS; i++) {
			LocalDate first = FIRST_MONTH.plusMonths(i);
			for (UUID category : expenseCategories) {
				if (first.equals(march) && (category.equals(r0) || category.equals(r0a))) {
					continue;
				}
				if (random.nextBoolean()) {
					long amount = random.nextInt(10) == 0 ? 0 : 1 + random.nextInt(40_000);
					f.insertMonthlyBudget(w, first, category, amount, u);
				}
			}
			if (random.nextInt(10) < 7) {
				f.insertMonthlyBudget(w, first, null, 1 + random.nextInt(200_000), u);
			}
		}
	}

	private void plant(WorkspaceFixtures f, UUID w, UUID u, UUID account, String name, boolean archived, long limit,
			LocalDate day, long... expenses) {
		UUID category = f.insertCategory(w, null, name, "EXPENSE", archived);
		LocalDate first = FIRST_MONTH.plusMonths(2);
		f.insertMonthlyBudget(w, first, category, limit, u);
		for (long cents : expenses) {
			f.insertLedgerTransaction(w, account, u, category, "EXPENSE", cents, day, "plantado " + name, false);
		}
	}

}
