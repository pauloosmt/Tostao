package com.tostao.tostao_api.recurrence;

import static com.tostao.tostao_api.recurrence.RecurrenceScenario.createRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.getRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.recurrencesFingerprint;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.recurrencesUrl;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.today;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.valid;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.with;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static com.tostao.tostao_api.transaction.PendingScenario.list;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsFingerprint;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.automation.AutomationService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/** História 2: criar uma recorrência e ver as ocorrências geradas adiante, tudo ou nada. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CreateRecurrenceTest {

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

	private static List<String> dates(String json) {
		return JsonPath.read(json, "$.occurrences[*].occurredOn");
	}

	private static List<LocalDate> expected(LocalDate start, String frequency, LocalDate end) {
		return RecurrenceCalendar.expected(start, frequency, end, today());
	}

	// ---- criar e gerar ----------------------------------------------------------------------------------------

	@Test
	void aMonthlyRentCreatesThirteenPendingOccurrencesUpToTwelveMonthsAhead() throws Exception {
		RecurrenceScenario s = scenario();

		MvcResult created = createRecurrence(mvc, s.owner(), RecurrenceScenario.with(valid(s))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.accountId").value(s.checking().toString()))
				.andExpect(jsonPath("$.categoryId").value(s.rent().toString()))
				.andExpect(jsonPath("$.type").value("EXPENSE"))
				.andExpect(jsonPath("$.amountCents").value(150_000))
				.andExpect(jsonPath("$.description").value("Aluguel"))
				.andExpect(jsonPath("$.frequency").value("MONTHLY"))
				.andExpect(jsonPath("$.startOn").value(today().toString()))
				.andExpect(jsonPath("$.autoClear").value(false))
				.andExpect(jsonPath("$.ended").value(false))
				.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString()))
				.andExpect(jsonPath("$.nextOccurrenceOn").value(today().toString()))
				.andExpect(jsonPath("$.totalCents").value(1_950_000))
				.andExpect(jsonPath("$.clearedCents").value(0))
				.andExpect(jsonPath("$.pendingCents").value(1_950_000)).andReturn();
		String json = created.getResponse().getContentAsString();
		String id = JsonPath.read(json, "$.id");

		assertThat(created.getResponse().getHeader("Location")).isEqualTo(recurrencesUrl(s.owner().workspaceId()) + "/" + id);
		assertThat(dates(json)).containsExactlyElementsOf(expected(today(), "MONTHLY", null).stream().map(LocalDate::toString).toList())
				.hasSize(13);
		assertThat(JsonPath.<List<Integer>>read(json, "$.occurrences[*].amountCents")).containsOnly(-150_000);
		assertThat(JsonPath.<List<String>>read(json, "$.occurrences[*].status")).containsOnly("PENDING");
		assertThat(JsonPath.<List<Boolean>>read(json, "$.occurrences[*].autoClear")).containsOnly(false);
		assertThat(JsonPath.<List<String>>read(json, "$.occurrences[*].recurrenceId")).containsOnly(id);
		assertThat(JsonPath.<List<Integer>>read(json, "$.occurrences[*].occurrenceNumber"))
				.containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);
		assertThat(JsonPath.<List<String>>read(json, "$.occurrences[*].description")).containsOnly("Aluguel");
		assertThat(JsonPath.<List<String>>read(json, "$.occurrences[*].categoryId")).containsOnly(s.rent().toString());
		assertThat(JsonPath.<List<String>>read(json, "$.occurrences[*].accountId")).containsOnly(s.checking().toString());
		assertThat(JsonPath.<List<String>>read(json, "$.occurrences[*].createdBy")).containsOnly(s.owner().userId().toString());
	}

	@Test
	void weeklyMonthlyAndYearlyFollowTheIndependentCalendar() throws Exception {
		RecurrenceScenario s = scenario();
		LocalDate[] starts = { today(), today().plusDays(3), today().minusDays(100), today().plusMonths(2) };

		for (String frequency : new String[] { "WEEKLY", "MONTHLY", "YEARLY" }) {
			for (LocalDate start : starts) {
				String json = body(createRecurrence(mvc, s.owner(), with(valid(s), "frequency", "\"" + frequency + "\"",
						"startOn", "\"" + start + "\"")).andExpect(status().isCreated()));

				assertThat(dates(json)).as("%s a partir de %s", frequency, start)
						.containsExactlyElementsOf(expected(start, frequency, null).stream().map(LocalDate::toString).toList());
			}
		}
	}

	@Test
	void theEndOfMonthAdjustmentNeverAccumulates() throws Exception {
		RecurrenceScenario s = scenario();
		// 31/01, 28/02, 31/03, 30/04: cada data é contada a partir do início, nunca da data anterior
		LocalDate thirtyFirst = LocalDate.of(2027, 1, 31);

		String json = body(createRecurrence(mvc, s.owner(), with(valid(s), "startOn", "\"" + thirtyFirst + "\""))
				.andExpect(status().isCreated()));

		List<String> got = dates(json);
		assertThat(got).containsExactlyElementsOf(expected(thirtyFirst, "MONTHLY", null).stream().map(LocalDate::toString).toList());
		assertThat(got.subList(0, 4)).containsExactly("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30");
	}

	@Test
	void theEndDateLimitsTheOccurrencesAndAnEndEqualToTheStartGivesOne() throws Exception {
		RecurrenceScenario s = scenario();

		String ended = body(createRecurrence(mvc, s.owner(), with(valid(s), "endOn", "\"" + today().plusMonths(2) + "\""))
				.andExpect(status().isCreated()));
		assertThat(dates(ended)).hasSize(3).containsExactlyElementsOf(
				expected(today(), "MONTHLY", today().plusMonths(2)).stream().map(LocalDate::toString).toList());

		String oneDay = body(createRecurrence(mvc, s.owner(), with(valid(s), "endOn", "\"" + today() + "\""))
				.andExpect(status().isCreated()));
		assertThat(dates(oneDay)).containsExactly(today().toString());
	}

	@Test
	void aFarFutureStartCreatesNothingUntilItEntersTheHorizon() throws Exception {
		RecurrenceScenario s = scenario();
		LocalDate start = today().plusYears(2);

		String json = body(createRecurrence(mvc, s.owner(), with(valid(s), "startOn", "\"" + start + "\""))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.nextOccurrenceOn").doesNotExist())
				.andExpect(jsonPath("$.totalCents").value(0)));
		assertThat(dates(json)).isEmpty();
		String id = JsonPath.read(json, "$.id");

		// um mês antes do início, o horizonte já o alcança: a execução automática gera as ocorrências
		automation.runOnce(start.minusMonths(1));

		String later = body(getRecurrence(mvc, s.owner(), id).andExpect(status().isOk()));
		assertThat(dates(later)).containsExactlyElementsOf(RecurrenceCalendar.expected(start, "MONTHLY", null, start.minusMonths(1))
				.stream().map(LocalDate::toString).toList()).isNotEmpty();
	}

	@Test
	void aStartUpTo366DaysAgoIsAcceptedAndGeneratesFromThereOn() throws Exception {
		RecurrenceScenario s = scenario();
		LocalDate start = today().minusDays(366);

		String json = body(createRecurrence(mvc, s.owner(), with(valid(s), "startOn", "\"" + start + "\""))
				.andExpect(status().isCreated()));

		assertThat(dates(json)).containsExactlyElementsOf(expected(start, "MONTHLY", null).stream().map(LocalDate::toString).toList());
		assertThat(dates(json).get(0)).isEqualTo(start.toString());
		assertThat(JsonPath.<List<String>>read(json, "$.occurrences[*].status")).containsOnly("PENDING");
	}

	@Test
	void anIncomeRecurrenceGeneratesPositiveOccurrences() throws Exception {
		RecurrenceScenario s = scenario();

		String json = body(createRecurrence(mvc, s.owner(), with(valid(s), "type", "\"INCOME\"", "categoryId",
				"\"" + s.salary() + "\"", "amountCents", "500000", "description", "\"Salario\"")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.type").value("INCOME")));

		assertThat(JsonPath.<List<Integer>>read(json, "$.occurrences[*].amountCents")).containsOnly(500_000);
		assertThat(JsonPath.<List<String>>read(json, "$.occurrences[*].type")).containsOnly("INCOME");
		assertThat(((Number) JsonPath.read(json, "$.totalCents")).longValue()).isEqualTo(13 * 500_000L);
	}

	@Test
	void theMarkerIsCopiedToEveryOccurrenceAndTheRunClearsTheOnesAlreadyDue() throws Exception {
		RecurrenceScenario s = scenario();
		LocalDate start = today().minusDays(40);

		String json = body(createRecurrence(mvc, s.owner(), with(valid(s), "startOn", "\"" + start + "\"", "autoClear", "true"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.autoClear").value(true)));
		String id = JsonPath.read(json, "$.id");
		assertThat(JsonPath.<List<Boolean>>read(json, "$.occurrences[*].autoClear")).containsOnly(true);
		assertThat(JsonPath.<List<String>>read(json, "$.occurrences[*].status")).containsOnly("PENDING");

		automation.runOnce(today());

		String after = body(getRecurrence(mvc, s.owner(), id).andExpect(status().isOk()));
		List<String> statuses = JsonPath.read(after, "$.occurrences[*].status");
		List<String> all = dates(after);
		long due = all.stream().filter(d -> !LocalDate.parse(d).isAfter(today())).count();
		assertThat(due).isGreaterThanOrEqualTo(2);
		assertThat(statuses.stream().filter("CLEARED"::equals).count()).isEqualTo(due);
		assertThat(statuses.subList(0, (int) due)).containsOnly("CLEARED");
		assertThat(JsonPath.<List<Boolean>>read(after, "$.occurrences[*].autoClear").subList(0, (int) due)).containsOnly(false);
		assertThat(JsonPath.<List<Boolean>>read(after, "$.occurrences[*].autoClear").subList((int) due, all.size())).containsOnly(true);
	}

	@Test
	void theNotesAreCopiedAndTheAuthorIsTheSessionUser() throws Exception {
		RecurrenceScenario s = scenario();

		String json = body(createRecurrence(mvc, s.owner(), with(valid(s), "notes", "\"Contrato de 30 meses\"",
				"frequency", "\"YEARLY\"")).andExpect(status().isCreated()).andExpect(jsonPath("$.notes").value("Contrato de 30 meses")));

		assertThat(JsonPath.<List<String>>read(json, "$.occurrences[*].notes")).containsOnly("Contrato de 30 meses");
		assertThat(JsonPath.<List<String>>read(json, "$.occurrences[*].createdBy")).containsOnly(s.owner().userId().toString());
		String blank = body(createRecurrence(mvc, s.owner(), with(valid(s), "notes", "\"   \"")).andExpect(status().isCreated()));
		assertThat(JsonPath.<Object>read(blank, "$.notes")).isNull();
	}

	@Test
	void theResponseIsNeverCached() throws Exception {
		RecurrenceScenario s = scenario();

		createRecurrence(mvc, s.owner(), with(valid(s))).andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", containsString("no-store")));
	}

	// ---- cada ocorrência é um lançamento comum ----------------------------------------------------------------

	@Test
	void everyOccurrenceIsAnOrdinaryTransactionThatDoesNotChangeTheRecurrence() throws Exception {
		RecurrenceScenario s = scenario();
		String json = body(createRecurrence(mvc, s.owner(), with(valid(s))).andExpect(status().isCreated()));
		String recurrenceId = JsonPath.read(json, "$.id");
		List<String> ids = JsonPath.read(json, "$.occurrences[*].id");
		UUID w = s.owner().workspaceId();

		// aparece na listagem, com a recorrência de origem e o número
		String listed = body(list(mvc, s.owner(), "size=100").andExpect(status().isOk()));
		assertThat(JsonPath.<List<String>>read(listed, "$.items[*].id")).containsExactlyInAnyOrderElementsOf(ids);

		// efetiva a primeira
		clear(mvc, s.owner(), UUID.fromString(ids.get(0))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLEARED"));

		// edita a segunda: valor, data e descrição mudam; a recorrência de origem e o número, não
		String edited = "{\"accountId\":\"%s\",\"amountCents\":160000,\"occurredOn\":\"%s\",\"description\":\"Aluguel reajustado\",\"categoryId\":\"%s\"}"
				.formatted(s.checking(), today().plusMonths(1).plusDays(2), s.rent());
		mvc.perform(put(transactionsUrl(w) + "/" + ids.get(1)).cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON).content(edited)).andExpect(status().isOk())
				.andExpect(jsonPath("$.amountCents").value(-160_000))
				.andExpect(jsonPath("$.recurrenceId").value(recurrenceId))
				.andExpect(jsonPath("$.occurrenceNumber").value(1));

		// exclui a terceira
		mvc.perform(delete(transactionsUrl(w) + "/" + ids.get(2)).cookie(session(s.owner().token())))
				.andExpect(status().isNoContent());

		// a recorrência continua a mesma e o detalhe reflete o que existe: 12 ocorrências e os totais certos
		getRecurrence(mvc, s.owner(), recurrenceId).andExpect(status().isOk())
				.andExpect(jsonPath("$.amountCents").value(150_000)).andExpect(jsonPath("$.description").value("Aluguel"))
				.andExpect(jsonPath("$.occurrences.length()").value(12))
				// efetivada a 0 (150000); pendentes: a 1 editada (160000) e as 3 a 12 (10 x 150000); a 2 foi excluída
				.andExpect(jsonPath("$.clearedCents").value(150_000))
				.andExpect(jsonPath("$.pendingCents").value(160_000 + 10 * 150_000L))
				.andExpect(jsonPath("$.totalCents").value(150_000 + 160_000 + 10 * 150_000L));
	}

	@Test
	void theRecurrenceOfOriginAndTheNumberCannotBeChangedByAnEdit() throws Exception {
		RecurrenceScenario s = scenario();
		String json = body(createRecurrence(mvc, s.owner(), with(valid(s))).andExpect(status().isCreated()));
		String second = JsonPath.<List<String>>read(json, "$.occurrences[*].id").get(1);
		String base = "{\"accountId\":\"%s\",\"amountCents\":150000,\"occurredOn\":\"%s\",\"description\":\"Aluguel\",\"categoryId\":\"%s\""
				.formatted(s.checking(), today().plusMonths(1), s.rent());

		for (String extra : new String[] { "\"recurrenceId\":\"" + UUID.randomUUID() + "\"", "\"occurrenceNumber\":7" }) {
			String before = transactionsFingerprint(owner, s.owner().workspaceId());
			mvc.perform(put(transactionsUrl(s.owner().workspaceId()) + "/" + second).cookie(session(s.owner().token()))
					.contentType(MediaType.APPLICATION_JSON).content(base + "," + extra + "}")).andExpect(status().isBadRequest());
			assertThat(transactionsFingerprint(owner, s.owner().workspaceId())).as(extra).isEqualTo(before);
		}
	}

	// ---- validação: nada é gravado ----------------------------------------------------------------------------

	private void refuse(RecurrenceScenario s, int expectedStatus, String field, String json) throws Exception {
		UUID w = s.owner().workspaceId();
		String transactions = transactionsFingerprint(owner, w);
		String recurrences = recurrencesFingerprint(owner, w);
		ResultActions result = createRecurrence(mvc, s.owner(), json).andExpect(status().is(expectedStatus));
		if (field != null) {
			assertThat(JsonPath.<List<String>>read(body(result), "$.errors[*].field")).as(json).contains(field);
		}
		assertThat(transactionsFingerprint(owner, w)).as("nenhum lançamento para " + json).isEqualTo(transactions);
		assertThat(recurrencesFingerprint(owner, w)).as("nenhuma recorrência para " + json).isEqualTo(recurrences);
	}

	@Test
	void anInvalidTypeIsRefusedOnItsField() throws Exception {
		RecurrenceScenario s = scenario();

		// (um número como 1 é aceito como a posição do enum, comportamento global do Jackson em todos os enums da API)
		for (String type : new String[] { "\"TRANSFER\"", "\"OUTRO\"", "\"\"", "null", null }) {
			refuse(s, 400, "type", with(valid(s), "type", type));
		}
	}

	@Test
	void anInvalidFrequencyIsRefusedOnItsField() throws Exception {
		RecurrenceScenario s = scenario();

		for (String frequency : new String[] { "\"DAILY\"", "\"monthly\"", "\"\"", "null", "3", null }) {
			refuse(s, 400, "frequency", with(valid(s), "frequency", frequency));
		}
	}

	@Test
	void anInvalidAmountIsRefusedOnItsField() throws Exception {
		RecurrenceScenario s = scenario();

		for (String amount : new String[] { "0", "-1", "10.5", "1000000000001", "null", "\"abc\"", null }) {
			refuse(s, 400, "amountCents", with(valid(s), "amountCents", amount));
		}
	}

	@Test
	void aStartOrEndOutsideTheRangeIsRefusedOnItsField() throws Exception {
		RecurrenceScenario s = scenario();

		for (String start : new String[] { "\"" + today().minusDays(367) + "\"", "\"2101-01-01\"", "\"texto\"", "\"2026-02-30\"", "null", null }) {
			refuse(s, 400, "startOn", with(valid(s), "startOn", start));
		}
		refuse(s, 400, "endOn", with(valid(s), "endOn", "\"" + today().minusDays(1) + "\""));
		refuse(s, 400, "endOn", with(valid(s), "endOn", "\"2101-01-01\""));
		refuse(s, 400, "endOn", with(valid(s), "endOn", "\"texto\""));
	}

	@Test
	void anInvalidDescriptionOrNotesIsRefusedOnItsField() throws Exception {
		RecurrenceScenario s = scenario();

		for (String description : new String[] { "\"\"", "\"   \"", "\"" + "x".repeat(201) + "\"", "null", null }) {
			refuse(s, 400, "description", with(valid(s), "description", description));
		}
		refuse(s, 400, "notes", with(valid(s), "notes", "\"" + "n".repeat(1001) + "\""));
		// o limite de cada ponta é aceito
		createRecurrence(mvc, s.owner(), with(valid(s), "description", "\"" + "x".repeat(200) + "\"")).andExpect(status().isCreated());
	}

	@Test
	void aNonBooleanMarkerAndMissingReferencesAreRefused() throws Exception {
		RecurrenceScenario s = scenario();

		refuse(s, 400, "autoClear", with(valid(s), "autoClear", "\"sim\""));
		refuse(s, 400, "accountId", with(valid(s), "accountId", null));
		refuse(s, 400, "categoryId", with(valid(s), "categoryId", null));
		refuse(s, 400, "accountId", with(valid(s), "accountId", "\"nao-e-uuid\""));
	}

	@Test
	void fieldsOutsideTheAllowedListAreRefused() throws Exception {
		RecurrenceScenario s = scenario();

		for (String extra : new String[] { "createdBy", "workspaceId", "recurrenceId", "status", "ended", "endedAt", "id",
				"occurrenceNumber", "createdAt" }) {
			refuse(s, 400, null, with(valid(s), extra, "\"" + UUID.randomUUID() + "\""));
		}
	}

	@Test
	void aCategoryOfTheOtherTypeIsRefusedOnTheCategoryField() throws Exception {
		RecurrenceScenario s = scenario();

		refuse(s, 400, "categoryId", with(valid(s), "categoryId", "\"" + s.salary() + "\""));
		refuse(s, 400, "categoryId", with(valid(s), "type", "\"INCOME\""));
	}

	@Test
	void anArchivedAccountOrCategoryIsAConflict() throws Exception {
		RecurrenceScenario s = scenario();
		UUID w = s.owner().workspaceId();
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		UUID archivedAccount = f.insertAccount(w, "Antiga", "CHECKING", 0, true);
		UUID archivedCategory = f.insertCategory(w, null, "Velha", "EXPENSE", true);

		refuse(s, 409, null, with(valid(s), "accountId", "\"" + archivedAccount + "\""));
		refuse(s, 409, null, with(valid(s), "categoryId", "\"" + archivedCategory + "\""));
	}

	@Test
	void anUnknownOrForeignAccountOrCategoryIsNotFound() throws Exception {
		RecurrenceScenario s = scenario();
		RecurrenceScenario other = scenario();

		refuse(s, 404, null, with(valid(s), "accountId", "\"" + UUID.randomUUID() + "\""));
		refuse(s, 404, null, with(valid(s), "categoryId", "\"" + UUID.randomUUID() + "\""));
		refuse(s, 404, null, with(valid(s), "accountId", "\"" + other.checking() + "\""));
		refuse(s, 404, null, with(valid(s), "categoryId", "\"" + other.rent() + "\""));
	}

	@Test
	void aMalformedBodyIsRefusedAndNothingIsWritten() throws Exception {
		RecurrenceScenario s = scenario();

		refuse(s, 400, null, "{");
		refuse(s, 400, null, "[]");
		refuse(s, 400, null, "");
	}

}
