package com.tostao.tostao_api.recurrence;

import static com.tostao.tostao_api.recurrence.RecurrenceScenario.createRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.endRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.getRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.listRecurrences;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.today;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.valid;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.with;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static com.tostao.tostao_api.transaction.PendingScenario.edit;
import static com.tostao.tostao_api.transaction.PendingScenario.transferBody;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

/**
 * História 3: ver e encerrar uma transferência recorrente (FR-010 a FR-012). A lista mostra todas; o detalhe traz as duas
 * pontas e conta cada ocorrência uma vez; encerrar exclui as duas pontas só das pendentes futuras e informa ocorrências, não
 * pontas; cada ocorrência se comporta como uma transferência comum.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class EndTransferRecurrenceTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	AutomationService automation;

	/** O banco é compartilhado: deixa de lado as recorrências e os lançamentos marcados de outros testes. */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
		owner.client().sql("select auto_clear_due(date '2100-12-31')").query(Integer.class).single();
	}

	private RecurrenceScenario scenario() {
		return RecurrenceScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	private static String json(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private static Map<String, String> transfer(RecurrenceScenario s, String frequency, LocalDate start) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("accountId", "\"" + s.checking() + "\"");
		fields.put("destinationAccountId", "\"" + s.savings() + "\"");
		fields.put("type", "\"TRANSFER\"");
		fields.put("amountCents", "50000");
		fields.put("description", "\"Reserva\"");
		fields.put("frequency", "\"" + frequency + "\"");
		fields.put("startOn", "\"" + start + "\"");
		return fields;
	}

	/** Cria a recorrência e devolve o detalhe. */
	private String create(RecurrenceScenario s, Map<String, String> body) throws Exception {
		return json(createRecurrence(mvc, s.owner(), with(body)).andExpect(status().isCreated()));
	}

	private static String id(String detail) {
		return JsonPath.read(detail, "$.id");
	}

	/** O id da ponta (saída se {@code out}, entrada se não) do número dado. */
	private static UUID leg(String detail, int number, boolean out) {
		List<String> ids = JsonPath.read(detail, "$.occurrences[?(@.occurrenceNumber == " + number + " && @.amountCents "
				+ (out ? "<" : ">") + " 0)].id");
		return UUID.fromString(ids.get(0));
	}

	private long liveRows(String recurrence) {
		return owner.client().sql("select count(*) from transactions where recurrence_id = cast(:r as uuid) and deleted_at is null")
				.param("r", recurrence).query(Long.class).single();
	}

	private long liveFuturePending(String recurrence) {
		return owner.client().sql("""
				select count(*) from transactions where recurrence_id = cast(:r as uuid) and deleted_at is null
				  and status = 'PENDING' and occurred_on > :today""").param("r", recurrence).param("today", today()).query(Long.class).single();
	}

	// ---- ver --------------------------------------------------------------------------------------------------

	@Test
	void theListShowsTransfersAndTheOthersEachWithItsOwnFields() throws Exception {
		RecurrenceScenario s = scenario();
		String expense = create(s, valid(s));
		String transfer = create(s, transfer(s, "MONTHLY", today()));

		String list = json(listRecurrences(mvc, s.owner()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2)));

		// a mais recente primeiro
		assertThat(JsonPath.<List<String>>read(list, "$[*].id")).containsExactly(id(transfer), id(expense));
		assertThat(JsonPath.<String>read(list, "$[0].type")).isEqualTo("TRANSFER");
		assertThat(JsonPath.<String>read(list, "$[0].destinationAccountId")).isEqualTo(s.savings().toString());
		assertThat(JsonPath.<Object>read(list, "$[0].categoryId")).isNull();
		assertThat(JsonPath.<String>read(list, "$[1].type")).isEqualTo("EXPENSE");
		assertThat(JsonPath.<String>read(list, "$[1].categoryId")).isEqualTo(s.rent().toString());
		assertThat(JsonPath.<Object>read(list, "$[1].destinationAccountId")).isNull();
		assertThat(JsonPath.<String>read(list, "$[0].nextOccurrenceOn")).isEqualTo(today().toString());
	}

	@Test
	void theDetailShowsBothLegsAndCountsEachOccurrenceOnce() throws Exception {
		RecurrenceScenario s = scenario();
		String detail = create(s, transfer(s, "MONTHLY", today()));
		clear(mvc, s.owner(), leg(detail, 0, true)).andExpect(status().isOk());

		String after = json(getRecurrence(mvc, s.owner(), id(detail)).andExpect(status().isOk()));

		assertThat(JsonPath.<List<Object>>read(after, "$.occurrences[*]")).hasSize(26);
		// efetivar uma ponta efetiva as duas, e a ocorrência conta uma vez
		assertThat(JsonPath.<List<String>>read(after, "$.occurrences[?(@.occurrenceNumber == 0)].status")).containsExactly("CLEARED", "CLEARED");
		assertThat(JsonPath.<Integer>read(after, "$.clearedCents")).isEqualTo(50_000);
		assertThat(JsonPath.<Integer>read(after, "$.pendingCents")).isEqualTo(600_000);
		assertThat(JsonPath.<Integer>read(after, "$.totalCents")).isEqualTo(650_000);
	}

	// ---- encerrar ---------------------------------------------------------------------------------------------

	@Test
	void endingDeletesBothLegsOfTheFutureOccurrencesOnlyAndCountsOccurrences() throws Exception {
		RecurrenceScenario s = scenario();
		// semanal, começando há 14 dias: as ocorrências 0 e 1 já venceram, a 2 é hoje, as demais são futuras
		String detail = create(s, transfer(s, "WEEKLY", today().minusDays(14)));
		clear(mvc, s.owner(), leg(detail, 1, false)).andExpect(status().isOk());
		long total = JsonPath.<List<Object>>read(detail, "$.occurrences[?(@.amountCents < 0)]").size();
		long futureOccurrences = total - 3;
		assertThat(liveFuturePending(id(detail))).isEqualTo(2 * futureOccurrences);

		endRecurrence(mvc, s.owner(), id(detail)).andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(futureOccurrences));

		assertThat(liveFuturePending(id(detail))).isZero();
		// ficam as três primeiras ocorrências, com as duas pontas (a efetivada e as vencidas)
		assertThat(liveRows(id(detail))).isEqualTo(6);
		String after = json(getRecurrence(mvc, s.owner(), id(detail)).andExpect(status().isOk()).andExpect(jsonPath("$.ended").value(true)));
		assertThat(JsonPath.<List<Integer>>read(after, "$.occurrences[*].occurrenceNumber")).containsExactlyInAnyOrder(0, 0, 1, 1, 2, 2);
		assertThat(JsonPath.<Integer>read(after, "$.clearedCents")).isEqualTo(50_000);
		assertThat(JsonPath.<Integer>read(after, "$.pendingCents")).isEqualTo(100_000);
	}

	@Test
	void endingAgainIsZeroAndAnEndedRecurrenceGeneratesNothingMore() throws Exception {
		RecurrenceScenario s = scenario();
		String detail = create(s, transfer(s, "MONTHLY", today()));

		endRecurrence(mvc, s.owner(), id(detail)).andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(12));
		endRecurrence(mvc, s.owner(), id(detail)).andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(0));
		automation.runOnce(today());

		// só a ocorrência de hoje (número 0) ficou, com as duas pontas, e a rotina não criou nada depois do encerramento
		assertThat(liveRows(id(detail))).isEqualTo(2);
		assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = cast(:r as uuid)").param("r", id(detail))
				.query(Long.class).single()).isEqualTo(26L);
	}

	@Test
	void anIncomeOrExpenseRecurrenceStillCountsRowsAsOccurrences() throws Exception {
		RecurrenceScenario s = scenario();
		String detail = create(s, valid(s));

		endRecurrence(mvc, s.owner(), id(detail)).andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(12));

		assertThat(liveRows(id(detail))).isEqualTo(1);
	}

	@Test
	void endingOneRecurrenceLeavesTheOthersAlone() throws Exception {
		RecurrenceScenario s = scenario();
		RecurrenceScenario other = scenario();
		String mine = create(s, transfer(s, "MONTHLY", today()));
		String theirs = create(other, transfer(other, "MONTHLY", today()));

		endRecurrence(mvc, s.owner(), id(mine)).andExpect(status().isOk());

		assertThat(liveRows(id(theirs))).isEqualTo(26);
	}

	// ---- cada ocorrência é uma transferência comum ------------------------------------------------------------

	@Test
	void editingAnOccurrenceChangesBothLegsAndTheRecurrenceKeepsItsNumber() throws Exception {
		RecurrenceScenario s = scenario();
		String detail = create(s, transfer(s, "MONTHLY", today()));
		LocalDate moved = today().plusDays(40);

		edit(mvc, s.owner(), leg(detail, 2, true), transferBody(s.checking(), s.savings(), 70_000, moved.toString(), "Reserva maior", null))
				.andExpect(status().isOk());

		String after = json(getRecurrence(mvc, s.owner(), id(detail)).andExpect(status().isOk()));
		List<Integer> amounts = JsonPath.read(after, "$.occurrences[?(@.occurrenceNumber == 2)].amountCents");
		List<String> dates = JsonPath.read(after, "$.occurrences[?(@.occurrenceNumber == 2)].occurredOn");
		assertThat(amounts).containsExactlyInAnyOrder(-70_000, 70_000);
		assertThat(dates).containsOnly(moved.toString());
		assertThat(JsonPath.<List<Object>>read(after, "$.occurrences[*]")).hasSize(26);
	}

	@Test
	void clearingAnOccurrenceClearsBothLegsAndDeletingOneRemovesBothAndItNeverComesBack() throws Exception {
		RecurrenceScenario s = scenario();
		String detail = create(s, transfer(s, "MONTHLY", today()));

		clear(mvc, s.owner(), leg(detail, 0, false)).andExpect(status().isOk());
		mvc.perform(delete(transactionsUrl(s.owner().workspaceId()) + "/" + leg(detail, 3, true)).cookie(session(s.owner().token())))
				.andExpect(status().isNoContent());
		automation.runOnce(today());

		String after = json(getRecurrence(mvc, s.owner(), id(detail)).andExpect(status().isOk()));
		assertThat(JsonPath.<List<String>>read(after, "$.occurrences[?(@.occurrenceNumber == 0)].status")).containsExactly("CLEARED", "CLEARED");
		assertThat(JsonPath.<List<Object>>read(after, "$.occurrences[?(@.occurrenceNumber == 3)]")).isEmpty();
		assertThat(JsonPath.<List<Object>>read(after, "$.occurrences[*]")).hasSize(24);
		// a rotina não recriou a ocorrência excluída: as duas linhas continuam ali, excluídas
		assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = cast(:r as uuid) and occurrence_number = 3 and deleted_at is not null")
				.param("r", id(detail)).query(Long.class).single()).isEqualTo(2L);
	}

}
