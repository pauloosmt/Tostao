package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchase;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/**
 * Dados aleatórios com semente fixa (SC-001, SC-002, SC-006): centenas de compras com totais e números de parcelas
 * sorteados, conferidas contra um <b>cálculo independente</b> escrito aqui (sem usar {@code InstallmentRules}); depois
 * parcelas efetivadas, excluídas e editadas ao acaso, com os totais do plano conferidos contra uma soma independente
 * sobre as linhas lidas do banco.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InstallmentRandomDataTest {

	private static final int PURCHASES = 150;
	private static final long MAX_TOTAL = 1_000_000_000_000L;

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	/** Uma compra sorteada, com o que se espera dela calculado à parte. */
	private record Draw(long total, int count, LocalDate first, boolean firstPending) {
	}

	private static Draw draw(Random random, int index) {
		int count = 2 + random.nextInt(47);
		long total = switch (index % 5) {
			case 0 -> count + random.nextInt(100); // poucos centavos: quase 1 por parcela
			case 1 -> MAX_TOTAL - random.nextInt(1_000); // no teto
			case 2 -> (long) count * (1 + random.nextInt(100_000)); // divisão exata
			default -> count + (long) (random.nextDouble() * (MAX_TOTAL - count));
		};
		// a primeira data: de 2000-01-01 a 2096-12-31, para a última (até 47 meses depois) caber até 2100-12-31
		long span = LocalDate.of(2096, 12, 31).toEpochDay() - LocalDate.of(2000, 1, 1).toEpochDay();
		LocalDate first = LocalDate.ofEpochDay(LocalDate.of(2000, 1, 1).toEpochDay() + (long) (random.nextDouble() * span));
		// dias 29, 30 e 31 aparecem muito: é onde o calendário erra
		if (index % 3 == 0) {
			YearMonth month = YearMonth.from(first);
			first = month.atDay(Math.min(29 + random.nextInt(3), month.lengthOfMonth()));
		}
		return new Draw(total, count, first, random.nextInt(4) == 0);
	}

	/** O valor da parcela {@code i} (de 1), por divisão e resto, escrito de outra forma que a do código de produção. */
	private static long expectedAmount(Draw d, int i) {
		long base = Math.floorDiv(d.total(), d.count());
		long rest = d.total() - base * d.count();
		return i <= rest ? base + 1 : base;
	}

	/** A data da parcela {@code i} (de 1), somando meses por aritmética de ano e mês, sem {@code plusMonths}. */
	private static LocalDate expectedDate(Draw d, int i) {
		int months = d.first().getYear() * 12 + (d.first().getMonthValue() - 1) + (i - 1);
		YearMonth target = YearMonth.of(months / 12, months % 12 + 1);
		return target.atDay(Math.min(d.first().getDayOfMonth(), target.lengthOfMonth()));
	}

	private void run(long seed) throws Exception {
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		InstallmentScenario s = InstallmentScenario.create(fixtures);
		UUID w = s.owner().workspaceId();
		Random random = new Random(seed);

		List<UUID> groups = new ArrayList<>();
		for (int index = 0; index < PURCHASES; index++) {
			Draw d = draw(random, index);
			String body = "{\"accountId\":\"%s\",\"categoryId\":\"%s\",\"totalCents\":%d,\"installmentCount\":%d,\"firstOccurredOn\":\"%s\",\"description\":\"Compra %d\"%s}"
					.formatted(s.checking(), s.gadgets(), d.total(), d.count(), d.first(), index,
							d.firstPending() ? ",\"firstInstallmentStatus\":\"PENDING\"" : "");
			String json = purchase(mvc, s.owner(), body).andExpect(status().isCreated()).andReturn().getResponse()
					.getContentAsString();
			groups.add(UUID.fromString(JsonPath.read(json, "$.groupId")));

			List<Number> amounts = JsonPath.read(json, "$.installments[*].amountCents");
			List<Integer> numbers = JsonPath.read(json, "$.installments[*].installmentNumber");
			List<String> dates = JsonPath.read(json, "$.installments[*].occurredOn");
			List<String> states = JsonPath.read(json, "$.installments[*].status");
			String context = "compra %d: %s".formatted(index, d);
			assertThat(amounts).as(context).hasSize(d.count());
			long sum = 0;
			long min = Long.MAX_VALUE;
			long max = Long.MIN_VALUE;
			for (int i = 1; i <= d.count(); i++) {
				// o JSON traz Integer ou Long conforme o tamanho
				long cents = -amounts.get(i - 1).longValue();
				sum += cents;
				min = Math.min(min, cents);
				max = Math.max(max, cents);
				assertThat(cents).as(context + " valor " + i).isEqualTo(expectedAmount(d, i));
				assertThat(dates.get(i - 1)).as(context + " data " + i).isEqualTo(expectedDate(d, i).toString());
				assertThat(numbers.get(i - 1)).as(context + " número " + i).isEqualTo(i);
				assertThat(states.get(i - 1)).as(context + " estado " + i)
						.isEqualTo(i == 1 && !d.firstPending() ? "CLEARED" : "PENDING");
			}
			assertThat(sum).as(context + " soma").isEqualTo(d.total());
			assertThat(max - min).as(context + " diferença").isLessThanOrEqualTo(1);
			assertThat(((Number) JsonPath.read(json, "$.totalCents")).longValue()).as(context + " totalCents")
					.isEqualTo(d.total());
		}

		// efetivar, excluir e editar parcelas ao acaso
		List<UUID> ids = owner.client().sql("select id from transactions where workspace_id = :w and installment_group_id is not null")
				.param("w", w).query(UUID.class).list();
		for (UUID id : ids) {
			int roll = random.nextInt(10);
			if (roll == 0) {
				clear(mvc, s.owner(), id).andExpect(status().isOk());
			}
			else if (roll == 1) {
				mvc.perform(delete(transactionsUrl(w) + "/" + id).cookie(session(s.owner().token())))
						.andExpect(status().isNoContent());
			}
			else if (roll == 2) {
				editAmount(s, w, id, 1 + random.nextInt(1_000_000));
			}
		}

		// os totais de cada plano batem com uma soma independente sobre as linhas não excluídas
		for (UUID group : groups) {
			record Row(long cents, String status) {
			}
			List<Row> rows = owner.client().sql("select amount_cents, status from transactions where workspace_id = :w "
					+ "and installment_group_id = :g and deleted_at is null").param("w", w).param("g", group)
					.query((rs, i) -> new Row(rs.getLong(1), rs.getString(2))).list();
			long total = rows.stream().mapToLong(r -> -r.cents()).sum();
			long cleared = rows.stream().filter(r -> r.status().equals("CLEARED")).mapToLong(r -> -r.cents()).sum();
			long pending = rows.stream().filter(r -> r.status().equals("PENDING")).mapToLong(r -> -r.cents()).sum();

			String plan = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
					.get(InstallmentScenario.purchasesUrl(w) + "/" + group).cookie(session(s.owner().token())))
					.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
			assertThat(((Number) JsonPath.read(plan, "$.totalCents")).longValue()).as("total " + group).isEqualTo(total);
			assertThat(((Number) JsonPath.read(plan, "$.clearedCents")).longValue()).as("efetivado " + group).isEqualTo(cleared);
			assertThat(((Number) JsonPath.read(plan, "$.pendingCents")).longValue()).as("pendente " + group).isEqualTo(pending);
			assertThat(((Number) JsonPath.read(plan, "$.totalCents")).longValue()).isEqualTo(cleared + pending);
			assertThat(JsonPath.<List<?>>read(plan, "$.installments")).hasSize(rows.size());
		}
	}

	private void editAmount(InstallmentScenario s, UUID w, UUID id, long cents) throws Exception {
		record Current(UUID account, UUID category, LocalDate date, String description) {
		}
		Current c = owner.client().sql("select account_id, category_id, occurred_on, description from transactions where id = :id")
				.param("id", id).query((rs, i) -> new Current(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
						rs.getObject(3, LocalDate.class), rs.getString(4))).single();
		String body = "{\"accountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"%s\",\"description\":\"%s\",\"categoryId\":\"%s\"}"
				.formatted(c.account(), cents, c.date(), c.description(), c.category());
		mvc.perform(put(transactionsUrl(w) + "/" + id).cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
	}

	@Test
	void everyRandomPurchaseIsSplitAndScheduledExactly() throws Exception {
		run(20261110L);
	}

	@Test
	void aSecondSeedAgrees() throws Exception {
		run(20261111L);
	}

}
