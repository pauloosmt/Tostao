package com.tostao.tostao_api.recurrence;

import static com.tostao.tostao_api.recurrence.RecurrenceScenario.createRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.endRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.getRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.listRecurrences;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.recurrencesFingerprint;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.today;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.valid;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.with;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.PendingScenario.account;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static com.tostao.tostao_api.transaction.PendingScenario.dashboard;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsFingerprint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.tostao.tostao_api.automation.AutomationService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/** História 3: encerrar a recorrência exclui só as ocorrências pendentes futuras e não mexe em nenhum número realizado. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EndRecurrenceTest {

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

	private RecurrenceScenario scenario() {
		return RecurrenceScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private long cancelled(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(body(result.andExpect(status().isOk())), "$.cancelled")).longValue();
	}

	private Object deletedAt(String id) {
		return owner.client().sql("select deleted_at from transactions where id = cast(:id as uuid)").param("id", id)
				.query((rs, i) -> rs.getTimestamp(1)).list().get(0); // pode ser nulo: single() não aceita
	}

	/** Início 40 dias atrás: as ocorrências 0 e 1 já venceram (pendentes), as demais são futuras. */
	private String past(RecurrenceScenario s) throws Exception {
		return body(createRecurrence(mvc, s.owner(), with(valid(s), "startOn", "\"" + today().minusDays(40) + "\""))
				.andExpect(status().isCreated()));
	}

	@Test
	void endingExcludesOnlyTheFuturePendingOccurrencesAndKeepsTheClearedAndTheOverdue() throws Exception {
		RecurrenceScenario s = scenario();
		String created = past(s);
		String id = JsonPath.read(created, "$.id");
		List<String> ids = JsonPath.read(created, "$.occurrences[*].id");
		int total = ids.size();
		// uma vencida efetivada à mão e uma futura efetivada à mão: as duas ficam
		clear(mvc, s.owner(), UUID.fromString(ids.get(1))).andExpect(status().isOk());
		clear(mvc, s.owner(), UUID.fromString(ids.get(3))).andExpect(status().isOk());

		long cancelled = cancelled(endRecurrence(mvc, s.owner(), id));

		// ficam: a 0 (pendente vencida), a 1 e a 3 (efetivadas); todas as outras (futuras e pendentes) saem
		assertThat(cancelled).isEqualTo(total - 3);
		for (int n : new int[] { 0, 1, 3 }) {
			assertThat(deletedAt(ids.get(n))).as("ocorrência %d fica", n).isNull();
		}
		for (int n = 0; n < total; n++) {
			if (n != 0 && n != 1 && n != 3) {
				assertThat(deletedAt(ids.get(n))).as("ocorrência %d sai", n).isNotNull();
			}
		}
		String detail = body(getRecurrence(mvc, s.owner(), id).andExpect(status().isOk())
				.andExpect(jsonPath("$.ended").value(true)).andExpect(jsonPath("$.endedAt").isNotEmpty())
				.andExpect(jsonPath("$.nextOccurrenceOn").doesNotExist()));
		assertThat(JsonPath.<List<Integer>>read(detail, "$.occurrences[*].occurrenceNumber")).containsExactly(0, 1, 3);
		assertThat(JsonPath.<List<String>>read(detail, "$.occurrences[*].status")).containsExactly("PENDING", "CLEARED", "CLEARED");
		assertThat(JsonPath.<List<Boolean>>read(body(listRecurrences(mvc, s.owner())), "$[*].ended")).containsExactly(true);
	}

	@Test
	void endingAgainOrWithNothingToExcludeDoesNothing() throws Exception {
		RecurrenceScenario s = scenario();
		String id = JsonPath.read(past(s), "$.id");
		endRecurrence(mvc, s.owner(), id).andExpect(status().isOk());
		String transactions = transactionsFingerprint(owner, s.owner().workspaceId());
		String recurrences = recurrencesFingerprint(owner, s.owner().workspaceId());

		assertThat(cancelled(endRecurrence(mvc, s.owner(), id))).isZero();
		assertThat(cancelled(endRecurrence(mvc, s.owner(), id))).isZero();

		assertThat(transactionsFingerprint(owner, s.owner().workspaceId())).isEqualTo(transactions);
		assertThat(recurrencesFingerprint(owner, s.owner().workspaceId())).isEqualTo(recurrences);
	}

	@Test
	void aRecurrenceWithNoFutureOccurrenceEndsWithZeroButIsEnded() throws Exception {
		RecurrenceScenario s = scenario();
		// só ocorrências vencidas
		String created = body(createRecurrence(mvc, s.owner(), with(valid(s), "startOn", "\"" + today().minusDays(40) + "\"",
				"endOn", "\"" + today().minusDays(5) + "\"")).andExpect(status().isCreated()));
		String id = JsonPath.read(created, "$.id");

		assertThat(cancelled(endRecurrence(mvc, s.owner(), id))).isZero();

		getRecurrence(mvc, s.owner(), id).andExpect(jsonPath("$.ended").value(true))
				.andExpect(jsonPath("$.occurrences.length()").value(2));
	}

	@Test
	void theAutomaticRunNeverGeneratesForAnEndedRecurrenceNorClearsWhatWasExcluded() throws Exception {
		RecurrenceScenario s = scenario();
		String created = body(createRecurrence(mvc, s.owner(), with(valid(s), "startOn", "\"" + today().minusDays(40) + "\"",
				"autoClear", "true")).andExpect(status().isCreated()));
		String id = JsonPath.read(created, "$.id");
		cancelled(endRecurrence(mvc, s.owner(), id));
		long rows = owner.client().sql("select count(*) from transactions where recurrence_id = cast(:id as uuid)")
				.param("id", id).query(Long.class).single();

		var result = automation.runOnce(today().plusYears(3));

		assertThat(result.generated()).isZero();
		assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = cast(:id as uuid)")
				.param("id", id).query(Long.class).single()).isEqualTo(rows);
		// as ocorrências excluídas, mesmo com o marcador, nunca são efetivadas
		assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = cast(:id as uuid) "
				+ "and deleted_at is not null and status = 'CLEARED'").param("id", id).query(Long.class).single()).isZero();
	}

	@Test
	void endingChangesNoRealizedNumberAndDropsTheForecast() throws Exception {
		RecurrenceScenario s = scenario();
		UUID w = s.owner().workspaceId();
		String rent = past(s);
		String rentId = JsonPath.read(rent, "$.id");
		// uma recorrência no cartão, com a ocorrência 0 (de cinco dias atrás) efetivada: entra na fatura
		String card = body(createRecurrence(mvc, s.owner(), with(valid(s), "accountId", "\"" + s.card() + "\"", "amountCents", "3000",
				"startOn", "\"" + today().minusDays(5) + "\"", "description", "\"Assinatura\"")).andExpect(status().isCreated()));
		String cardId = JsonPath.read(card, "$.id");
		List<String> cardIds = JsonPath.read(card, "$.occurrences[*].id");
		clear(mvc, s.owner(), UUID.fromString(cardIds.get(0))).andExpect(status().isOk());
		clear(mvc, s.owner(), UUID.fromString(JsonPath.<List<String>>read(rent, "$.occurrences[*].id").get(1))).andExpect(status().isOk());

		YearMonth month = YearMonth.from(today());
		List<String> before = realized(s, w, month);
		String accountBefore = body(account(mvc, s.owner(), s.checking()));

		long rentCancelled = cancelled(endRecurrence(mvc, s.owner(), rentId));
		long cardCancelled = cancelled(endRecurrence(mvc, s.owner(), cardId));

		// o realizado não muda: saldos, dashboard, saldo total e as faturas do cartão
		assertThat(realized(s, w, month)).isEqualTo(before);
		// o previsto da conta deixa de incluir as excluídas: cada uma valia 150000 (pendente, negativo)
		String accountAfter = body(account(mvc, s.owner(), s.checking()));
		assertThat(((Number) JsonPath.read(accountAfter, "$.balanceCents")).longValue())
				.isEqualTo(((Number) JsonPath.read(accountBefore, "$.balanceCents")).longValue());
		assertThat(((Number) JsonPath.read(accountAfter, "$.pendingCents")).longValue())
				.isEqualTo(((Number) JsonPath.read(accountBefore, "$.pendingCents")).longValue() + rentCancelled * 150_000L);
		assertThat(rentCancelled).isGreaterThan(0);
		assertThat(cardCancelled).isGreaterThan(0);
	}

	/** Tudo que é realizado: saldos das contas, saldo total, o dashboard de três meses e as faturas do cartão. */
	private List<String> realized(RecurrenceScenario s, UUID w, YearMonth month) throws Exception {
		List<String> snapshot = new ArrayList<>();
		for (UUID accountId : new UUID[] { s.checking(), s.savings(), s.card() }) {
			snapshot.add(JsonPath.read(body(account(mvc, s.owner(), accountId)), "$.balanceCents").toString());
		}
		// só o realizado do relatório: o previsto (pendingCents e projectedBalanceCents) deve mudar com o encerramento
		String report = body(mvc.perform(get("/api/v1/workspaces/" + w + "/reports/balance").cookie(session(s.owner().token()))));
		snapshot.add(JsonPath.read(report, "$.totalBalanceCents").toString());
		snapshot.add(JsonPath.read(report, "$.accounts[*].balanceCents").toString());
		for (int i = 0; i < 3; i++) {
			String json = body(dashboard(mvc, s.owner(), "month=" + month.plusMonths(i)));
			snapshot.add(JsonPath.read(json, "$.totals").toString());
		}
		for (int i = 0; i < 3; i++) {
			var result = mvc.perform(get("/api/v1/workspaces/" + w + "/accounts/" + s.card() + "/invoices/" + month.plusMonths(i))
					.cookie(session(s.owner().token()))).andReturn().getResponse();
			snapshot.add(result.getStatus() + ":" + result.getContentAsString());
		}
		return snapshot;
	}

	@Test
	void theClearedOccurrencesAreNeverTouched() throws Exception {
		RecurrenceScenario s = scenario();
		String created = past(s);
		String id = JsonPath.read(created, "$.id");
		String cleared = JsonPath.<List<String>>read(created, "$.occurrences[*].id").get(4);
		clear(mvc, s.owner(), UUID.fromString(cleared)).andExpect(status().isOk());
		String rowBefore = owner.client().sql("select x::text from transactions x where id = cast(:id as uuid)")
				.param("id", cleared).query(String.class).single();

		endRecurrence(mvc, s.owner(), id).andExpect(status().isOk());

		assertThat(owner.client().sql("select x::text from transactions x where id = cast(:id as uuid)").param("id", cleared)
				.query(String.class).single()).isEqualTo(rowBefore);
	}

	@Test
	void anUnknownMalformedOrForeignRecurrenceIsNotFoundAndNothingChanges() throws Exception {
		RecurrenceScenario mine = scenario();
		RecurrenceScenario theirs = scenario();
		String foreign = JsonPath.read(past(theirs), "$.id");
		String myTransactions = transactionsFingerprint(owner, mine.owner().workspaceId());
		String theirTransactions = transactionsFingerprint(owner, theirs.owner().workspaceId());
		String theirRecurrences = recurrencesFingerprint(owner, theirs.owner().workspaceId());

		endRecurrence(mvc, mine.owner(), UUID.randomUUID()).andExpect(status().isNotFound());
		endRecurrence(mvc, mine.owner(), "abc").andExpect(status().isNotFound());
		endRecurrence(mvc, mine.owner(), foreign).andExpect(status().isNotFound());

		assertThat(transactionsFingerprint(owner, mine.owner().workspaceId())).isEqualTo(myTransactions);
		assertThat(transactionsFingerprint(owner, theirs.owner().workspaceId())).isEqualTo(theirTransactions);
		assertThat(recurrencesFingerprint(owner, theirs.owner().workspaceId())).isEqualTo(theirRecurrences);
	}

	@Test
	void theResponseIsNeverCached() throws Exception {
		RecurrenceScenario s = scenario();
		String id = JsonPath.read(past(s), "$.id");

		endRecurrence(mvc, s.owner(), id).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")));
	}

}
