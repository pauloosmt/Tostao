package com.tostao.tostao_api.invoice;

import static com.tostao.tostao_api.invoice.InvoiceScenario.list;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * SC-001 a SC-003: cartões com dias de fechamento e vencimento aleatórios (inclusive 29, 30 e 31) e lançamentos
 * aleatórios, muitos deles no dia do fechamento, no dia seguinte e em fim de mês. O total e o pago de cada
 * fatura são conferidos contra um cálculo <b>independente</b>: em vez de montar o período de cada fatura como o
 * código faz, o teste parte de cada lançamento e procura o primeiro fechamento que não é anterior a ele.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InvoiceRandomDataTest {

	private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
	private static final LocalDate FIRST_DAY = LocalDate.of(2024, 1, 1);
	private static final LocalDate LAST_DAY = LocalDate.of(2026, 6, 30);

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private enum Kind {
		EXPENSE, INCOME, TRANSFER_OUT, TRANSFER_IN
	}

	private record Tx(LocalDate date, Kind kind, long cents, boolean deleted) {
		/** Valor na fatura: compra e saque somam, estorno subtrai; pagamento e excluído não compõem o total. */
		long charge() {
			if (deleted) {
				return 0;
			}
			return switch (kind) {
				case EXPENSE, TRANSFER_OUT -> cents;
				case INCOME -> -cents;
				case TRANSFER_IN -> 0;
			};
		}
	}

	@Test
	void everyInvoiceMatchesAnIndependentCalculationForTwoSeeds() throws Exception {
		run(20261109L);
		run(20261110L);
	}

	private void run(long seed) throws Exception {
		Random random = new Random(seed);
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID checking = fixtures.insertAccount(w, "Corrente", "CHECKING", 0, false);
		UUID food = fixtures.insertCategory(w, null, "Mercado", "EXPENSE", false);
		LocalDate today = LocalDate.now(BRASILIA);

		for (int c = 0; c < 6; c++) {
			// metade dos cartões com dias de fim de mês, para estressar o ajuste de 29, 30 e 31
			int closingDay = c < 3 ? 28 + random.nextInt(4) : 1 + random.nextInt(31);
			int dueDay = c % 2 == 0 ? 1 + random.nextInt(31) : 28 + random.nextInt(4);
			UUID card = fixtures.insertCard(w, "Cartão " + c, 0, null, closingDay, dueDay, false);

			List<Tx> txs = new ArrayList<>();
			for (int i = 0; i < 500; i++) {
				Tx tx = randomTx(random, closingDay);
				txs.add(tx);
				insert(fixtures, fx, w, card, checking, food, tx);
			}
			verify(fx, card, closingDay, dueDay, txs, today, "semente " + seed + ", cartão " + closingDay + "/" + dueDay);
		}
	}

	private static Tx randomTx(Random random, int closingDay) {
		LocalDate date;
		if (random.nextBoolean()) {
			date = FIRST_DAY.plusDays(random.nextInt((int) (LAST_DAY.toEpochDay() - FIRST_DAY.toEpochDay()) + 1));
		} else {
			// perto do fechamento e do fim do mês, onde os erros de calendário aparecem
			YearMonth month = YearMonth.from(FIRST_DAY).plusMonths(random.nextInt(30));
			int closing = Math.min(closingDay, month.lengthOfMonth());
			int[] days = { closing, Math.min(closing + 1, month.lengthOfMonth()), month.lengthOfMonth(), 1 };
			date = month.atDay(days[random.nextInt(days.length)]);
		}
		int roll = random.nextInt(100);
		Kind kind = roll < 60 ? Kind.EXPENSE : roll < 70 ? Kind.INCOME : roll < 80 ? Kind.TRANSFER_OUT : Kind.TRANSFER_IN;
		boolean deleted = (kind == Kind.EXPENSE || kind == Kind.INCOME) && random.nextInt(20) == 0;
		return new Tx(date, kind, 1 + random.nextInt(200_000), deleted);
	}

	private void insert(WorkspaceFixtures fixtures, Fixture fx, UUID w, UUID card, UUID checking, UUID food, Tx tx) {
		switch (tx.kind()) {
			case EXPENSE -> fixtures.insertLedgerTransaction(w, card, fx.userId(), food, "EXPENSE", tx.cents(),
					tx.date(), "Compra", tx.deleted());
			case INCOME -> fixtures.insertLedgerTransaction(w, card, fx.userId(), null, "INCOME", tx.cents(),
					tx.date(), "Estorno", tx.deleted());
			case TRANSFER_OUT -> fixtures.insertTransferPair(w, card, checking, fx.userId(), tx.cents(), tx.date(),
					"Saque");
			case TRANSFER_IN -> fixtures.insertTransferPair(w, checking, card, fx.userId(), tx.cents(), tx.date(),
					"Pagamento");
		}
	}

	private void verify(Fixture fx, UUID card, int closingDay, int dueDay, List<Tx> txs, LocalDate today, String label)
			throws Exception {
		// fechamentos de cada mês civil, em ordem; o de um mês é o dia cadastrado, limitado ao tamanho do mês
		List<LocalDate> closings = new ArrayList<>();
		for (YearMonth month = YearMonth.of(2023, 1); !month.isAfter(YearMonth.of(2027, 6)); month = month.plusMonths(1)) {
			closings.add(month.atDay(Math.min(closingDay, month.lengthOfMonth())));
		}

		// cada lançamento procura o primeiro fechamento que não é anterior a ele; a fatura desse fechamento vence
		// no mesmo mês do fechamento quando dueDay > closingDay e no mês seguinte quando não
		Map<YearMonth, Long> expectedTotal = new HashMap<>();
		Map<YearMonth, Long> expectedPaidRaw = new HashMap<>();
		long countedCharges = 0;
		for (Tx tx : txs) {
			if (tx.deleted()) {
				continue;
			}
			LocalDate closing = closings.stream().filter(d -> !d.isBefore(tx.date())).findFirst().orElseThrow();
			YearMonth due = dueDay > closingDay ? YearMonth.from(closing) : YearMonth.from(closing).plusMonths(1);
			if (tx.kind() == Kind.TRANSFER_IN) {
				// um pagamento feito durante o período de uma fatura é da fatura anterior
				expectedPaidRaw.merge(due.minusMonths(1), tx.cents(), Long::sum);
			} else {
				expectedTotal.merge(due, tx.charge(), Long::sum);
				countedCharges += tx.charge();
			}
		}

		String json = list(mvc, fx, card, "from=2024-01&to=2026-12").andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		List<Map<String, Object>> invoices = JsonPath.read(json, "$.invoices");
		assertThat(invoices).as(label).hasSize(36);

		long sumOfTotals = 0;
		for (Map<String, Object> invoice : invoices) {
			YearMonth due = YearMonth.parse((String) invoice.get("month"));
			long total = ((Number) invoice.get("totalCents")).longValue();
			long paid = ((Number) invoice.get("paidCents")).longValue();
			long remaining = ((Number) invoice.get("remainingCents")).longValue();
			YearMonth closingMonth = dueDay > closingDay ? due : due.minusMonths(1);
			LocalDate closing = closingMonth.atDay(Math.min(closingDay, closingMonth.lengthOfMonth()));
			String where = label + ", fatura " + due;

			long expectedTotalHere = expectedTotal.getOrDefault(due, 0L);
			// só o que já fechou conta como pago; a fatura aberta mostra pago zero
			long expectedPaid = today.isAfter(closing) ? expectedPaidRaw.getOrDefault(due, 0L) : 0;
			assertThat(total).as(where + " total").isEqualTo(expectedTotalHere);
			assertThat(paid).as(where + " pago").isEqualTo(expectedPaid);
			assertThat(remaining).as(where + " restante").isEqualTo(Math.max(expectedTotalHere - expectedPaid, 0));
			assertThat(LocalDate.parse((String) invoice.get("closingDate"))).as(where + " fechamento").isEqualTo(closing);
			sumOfTotals += total;
		}
		// SC-002: cada lançamento pertence a exatamente uma fatura, então a soma das faturas é a soma dos lançamentos
		assertThat(sumOfTotals).as(label + " soma das faturas").isEqualTo(countedCharges);
	}

}
