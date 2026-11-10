package com.tostao.tostao_api.recurrence;

import static com.tostao.tostao_api.recurrence.RecurrenceScenario.createRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.today;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.tostao.tostao_api.automation.AutomationService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/**
 * Dados aleatórios com semente fixa (SC-003, SC-004): 120 recorrências com frequência, valor, início, fim e marcador
 * sorteados, criadas pela API e processadas por execuções automáticas em datas crescentes sorteadas (até 2100), conferidas
 * contra um <b>cálculo independente</b> escrito no teste. Ocorrências excluídas à mão nunca voltam e nunca há duplicata.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RecurrenceRandomDataTest {

	private static final int RECURRENCES = 120;

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

	private record Rec(UUID id, LocalDate start, String frequency, LocalDate end, boolean autoClear) {
	}

	private record Row(int number, LocalDate date, String status, boolean autoClear, boolean deleted) {
	}

	private static LocalDate randomDate(Random random, LocalDate from, LocalDate to) {
		return LocalDate.ofEpochDay(from.toEpochDay() + (long) (random.nextDouble() * (to.toEpochDay() - from.toEpochDay())));
	}

	private void run(long seed) throws Exception {
		RecurrenceScenario s = RecurrenceScenario.create(new WorkspaceFixtures(owner, tokens));
		Random random = new Random(seed);
		String[] frequencies = { "WEEKLY", "MONTHLY", "YEARLY" };
		LocalDate limit = LocalDate.of(2100, 12, 31);

		List<Rec> recs = new ArrayList<>();
		for (int i = 0; i < RECURRENCES; i++) {
			String frequency = frequencies[random.nextInt(3)];
			// 60% perto de hoje (de 366 dias atrás a 400 dias à frente) e 40% bem no futuro, até 2100
			LocalDate start = random.nextInt(10) < 6 ? randomDate(random, today().minusDays(366), today().plusDays(400))
					: randomDate(random, today().plusDays(401), LocalDate.of(2099, 12, 31));
			// dias 29, 30 e 31 aparecem muito: é onde o calendário erra
			if (i % 4 == 0) {
				start = start.withDayOfMonth(Math.min(29 + random.nextInt(3), start.lengthOfMonth()));
			}
			LocalDate end = random.nextInt(10) < 4 ? min(limit, start.plusDays(1 + random.nextInt(1_200))) : null;
			boolean income = random.nextBoolean();
			boolean autoClear = random.nextInt(3) == 0;
			long amount = switch (i % 4) {
				case 0 -> 1 + random.nextInt(100);
				case 1 -> 1_000_000_000_000L - random.nextInt(1_000);
				default -> 1 + (long) (random.nextDouble() * 1_000_000_000_000L);
			};
			String body = "{\"accountId\":\"%s\",\"categoryId\":\"%s\",\"type\":\"%s\",\"amountCents\":%d,\"description\":\"Sorteio %d\",\"frequency\":\"%s\",\"startOn\":\"%s\"%s,\"autoClear\":%s}"
					.formatted(s.checking(), income ? s.salary() : s.rent(), income ? "INCOME" : "EXPENSE", amount, i, frequency,
							start, end == null ? "" : ",\"endOn\":\"" + end + "\"", autoClear);
			String json = createRecurrence(mvc, s.owner(), body).andExpect(status().isCreated()).andReturn().getResponse()
					.getContentAsString();
			recs.add(new Rec(UUID.fromString(JsonPath.read(json, "$.id")), start, frequency, end, autoClear));
		}

		// datas de execução crescentes, a primeira hoje e a última perto de 2100
		TreeSet<LocalDate> runs = new TreeSet<>();
		runs.add(today());
		while (runs.size() < 6) {
			runs.add(randomDate(random, today(), LocalDate.of(2100, 6, 1)));
		}
		boolean deletedAlready = false;
		Map<UUID, TreeSet<Integer>> deleted = new HashMap<>();
		for (LocalDate day : runs) {
			automation.runOnce(day);
			verify(recs, day, deleted);
			if (!deletedAlready) {
				// depois da primeira execução, exclui ao acaso algumas ocorrências (e muda o valor de outras)
				deleteSome(recs, random, deleted);
				deletedAlready = true;
			}
		}
		// uma execução a mais, na última data, não muda nada
		assertThat(automation.runOnce(runs.last()).generated()).isZero();
	}

	private static LocalDate min(LocalDate a, LocalDate b) {
		return a.isBefore(b) ? a : b;
	}

	private List<Row> rows(UUID recurrence) {
		return owner.client().sql("select occurrence_number, occurred_on, status, auto_clear, deleted_at is not null "
				+ "from transactions where recurrence_id = :r order by occurrence_number").param("r", recurrence)
				.query((rs, i) -> new Row(rs.getInt(1), rs.getObject(2, LocalDate.class), rs.getString(3), rs.getBoolean(4),
						rs.getBoolean(5))).list();
	}

	private void deleteSome(List<Rec> recs, Random random, Map<UUID, TreeSet<Integer>> deleted) {
		for (Rec rec : recs) {
			for (Row row : rows(rec.id())) {
				if (random.nextInt(10) == 0) {
					owner.client().sql("update transactions set deleted_at = now() where recurrence_id = :r and occurrence_number = :n")
							.param("r", rec.id()).param("n", row.number()).update();
					deleted.computeIfAbsent(rec.id(), k -> new TreeSet<>()).add(row.number());
				}
				else if (random.nextInt(10) == 0) {
					owner.client().sql("update transactions set amount_cents = amount_cents / 2 - 1 where recurrence_id = :r and occurrence_number = :n")
							.param("r", rec.id()).param("n", row.number()).update();
				}
			}
		}
	}

	/** Tudo o que existe depois de uma execução na data {@code day} bate com o cálculo independente. */
	private void verify(List<Rec> recs, LocalDate day, Map<UUID, TreeSet<Integer>> deleted) {
		for (Rec rec : recs) {
			List<LocalDate> expected = RecurrenceCalendar.expected(rec.start(), rec.frequency(), rec.end(), day);
			List<Row> rows = rows(rec.id());
			String context = "%s a partir de %s (fim %s, execução %s)".formatted(rec.frequency(), rec.start(), rec.end(), day);

			// cobre exatamente as datas até 12 meses depois da execução, sem repetir número, nem passar do fim ou de 2100
			assertThat(rows).as(context).hasSize(expected.size());
			for (int n = 0; n < rows.size(); n++) {
				assertThat(rows.get(n).number()).as(context).isEqualTo(n);
				assertThat(rows.get(n).date()).as(context + " ocorrência " + n).isEqualTo(expected.get(n));
				assertThat(rows.get(n).date()).isBeforeOrEqualTo(RecurrenceCalendar.LIMIT);
				if (rec.end() != null) {
					assertThat(rows.get(n).date()).isBeforeOrEqualTo(rec.end());
				}
			}
			// excluída à mão nunca volta
			for (int n : deleted.getOrDefault(rec.id(), new TreeSet<>())) {
				assertThat(rows.get(n).deleted()).as(context + " excluída " + n).isTrue();
			}
			// o estado: com o marcador, as vivas vencidas estão efetivadas e as futuras seguem marcadas; sem, tudo pendente
			for (Row row : rows) {
				if (row.deleted()) {
					continue;
				}
				if (rec.autoClear() && !row.date().isAfter(day)) {
					assertThat(row.status()).as(context + " vencida " + row.number()).isEqualTo("CLEARED");
					assertThat(row.autoClear()).isFalse();
				}
				else {
					assertThat(row.status()).as(context + " " + row.number()).isEqualTo("PENDING");
					assertThat(row.autoClear()).isEqualTo(rec.autoClear());
				}
			}
		}
	}

	@Test
	void everyRandomRecurrenceFollowsTheIndependentCalendarAcrossRuns() throws Exception {
		run(20261110L);
	}

	@Test
	void aSecondSeedAgrees() throws Exception {
		run(20261111L);
	}

}
