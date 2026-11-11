package com.tostao.tostao_api.recurrence;

import static com.tostao.tostao_api.recurrence.RecurrenceScenario.createRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.recurrencesFingerprint;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.recurrencesUrl;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.today;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.valid;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.with;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsFingerprint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/**
 * História 1: criar uma transferência recorrente (FR-001 a FR-008). As ocorrências nascem em pares pendentes e sem categoria, a
 * forma é conferida por campo, as duas contas são conferidas e travadas antes de gravar, e a criação é tudo ou nada. Receita e
 * despesa seguem como na 011.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CreateTransferRecurrenceTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

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

	/** Uma transferência mensal de 50000 da Corrente para a Poupança, a partir de hoje. */
	private static Map<String, String> transfer(RecurrenceScenario s) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("accountId", "\"" + s.checking() + "\"");
		fields.put("destinationAccountId", "\"" + s.savings() + "\"");
		fields.put("type", "\"TRANSFER\"");
		fields.put("amountCents", "50000");
		fields.put("description", "\"Reserva\"");
		fields.put("frequency", "\"MONTHLY\"");
		fields.put("startOn", "\"" + today() + "\"");
		return fields;
	}

	private void refuse(RecurrenceScenario s, int expectedStatus, String field, String json) throws Exception {
		UUID w = s.owner().workspaceId();
		String transactions = transactionsFingerprint(owner, w);
		String recurrences = recurrencesFingerprint(owner, w);
		ResultActions result = createRecurrence(mvc, s.owner(), json).andExpect(status().is(expectedStatus));
		if (field != null) {
			assertThat(JsonPath.<List<String>>read(json(result), "$.errors[*].field")).as(json).contains(field);
		}
		assertThat(transactionsFingerprint(owner, w)).as("nenhum lançamento para " + json).isEqualTo(transactions);
		assertThat(recurrencesFingerprint(owner, w)).as("nenhuma recorrência para " + json).isEqualTo(recurrences);
	}

	// ---- criar e gerar ----------------------------------------------------------------------------------------

	@Test
	void aMonthlyTransferCreatesThirteenPairsOfPendingLegsWithoutCategory() throws Exception {
		RecurrenceScenario s = scenario();

		MvcResult created = createRecurrence(mvc, s.owner(), with(transfer(s))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.type").value("TRANSFER"))
				.andExpect(jsonPath("$.accountId").value(s.checking().toString()))
				.andExpect(jsonPath("$.destinationAccountId").value(s.savings().toString()))
				.andExpect(jsonPath("$.categoryId").doesNotExist())
				.andExpect(jsonPath("$.amountCents").value(50_000))
				.andExpect(jsonPath("$.autoClear").value(false))
				.andExpect(jsonPath("$.ended").value(false))
				.andExpect(jsonPath("$.nextOccurrenceOn").value(today().toString()))
				// cada ocorrência conta uma vez nos totais, não duas
				.andExpect(jsonPath("$.totalCents").value(650_000))
				.andExpect(jsonPath("$.clearedCents").value(0))
				.andExpect(jsonPath("$.pendingCents").value(650_000)).andReturn();
		String body = created.getResponse().getContentAsString();
		String id = JsonPath.read(body, "$.id");

		assertThat(created.getResponse().getHeader("Location")).isEqualTo(recurrencesUrl(s.owner().workspaceId()) + "/" + id);
		List<LocalDate> expected = RecurrenceCalendar.expected(today(), "MONTHLY", null, today());
		assertThat(expected).hasSize(13);
		List<String> outDates = JsonPath.read(body, "$.occurrences[?(@.amountCents < 0)].occurredOn");
		List<String> inDates = JsonPath.read(body, "$.occurrences[?(@.amountCents > 0)].occurredOn");
		assertThat(outDates).containsExactlyElementsOf(expected.stream().map(LocalDate::toString).toList());
		assertThat(inDates).containsExactlyElementsOf(outDates);
		assertThat(JsonPath.<List<Object>>read(body, "$.occurrences[*]")).hasSize(26);
		assertThat(JsonPath.<List<Integer>>read(body, "$.occurrences[?(@.amountCents < 0)].amountCents")).containsOnly(-50_000);
		assertThat(JsonPath.<List<Integer>>read(body, "$.occurrences[?(@.amountCents > 0)].amountCents")).containsOnly(50_000);
		assertThat(JsonPath.<List<String>>read(body, "$.occurrences[?(@.amountCents < 0)].accountId")).containsOnly(s.checking().toString());
		assertThat(JsonPath.<List<String>>read(body, "$.occurrences[?(@.amountCents > 0)].accountId")).containsOnly(s.savings().toString());
		assertThat(JsonPath.<List<String>>read(body, "$.occurrences[*].type")).containsOnly("TRANSFER");
		assertThat(JsonPath.<List<String>>read(body, "$.occurrences[*].status")).containsOnly("PENDING");
		assertThat(JsonPath.<List<Object>>read(body, "$.occurrences[*].categoryId")).containsOnlyNulls();
		assertThat(JsonPath.<List<String>>read(body, "$.occurrences[*].recurrenceId")).containsOnly(id);
		assertThat(JsonPath.<List<String>>read(body, "$.occurrences[*].createdBy")).containsOnly(s.owner().userId().toString());
		assertThat(JsonPath.<List<String>>read(body, "$.occurrences[*].description")).containsOnly("Reserva");
		// as duas pontas de cada número têm o mesmo grupo; os números vão de 0 a 12, duas vezes cada
		List<Integer> numbers = JsonPath.read(body, "$.occurrences[*].occurrenceNumber");
		assertThat(numbers).hasSize(26).containsOnlyElementsOf(java.util.stream.IntStream.range(0, 13).boxed().toList());
		List<String> groups = JsonPath.read(body, "$.occurrences[*].transferGroupId");
		assertThat(groups.stream().distinct()).hasSize(13);
		for (int number = 0; number < 13; number++) {
			List<String> pair = JsonPath.read(body, "$.occurrences[?(@.occurrenceNumber == " + number + ")].transferGroupId");
			assertThat(pair).hasSize(2);
			assertThat(pair.get(0)).isEqualTo(pair.get(1));
		}
	}

	@Test
	void pendingOccurrencesNeverMoveABalance() throws Exception {
		RecurrenceScenario s = scenario();

		createRecurrence(mvc, s.owner(), with(transfer(s))).andExpect(status().isCreated());

		String accounts = json(mvc.perform(get("/api/v1/workspaces/" + s.owner().workspaceId() + "/accounts")
				.cookie(session(s.owner().token()))).andExpect(status().isOk()));
		assertThat(JsonPath.<List<Integer>>read(accounts, "$[?(@.name=='Corrente')].balanceCents")).containsExactly(500_000);
		assertThat(JsonPath.<List<Integer>>read(accounts, "$[?(@.name=='Poupança')].balanceCents")).containsExactly(0);
	}

	@Test
	void theMarkerWeekAndYearAndTheEndDateAreHonored() throws Exception {
		RecurrenceScenario s = scenario();

		String body = json(createRecurrence(mvc, s.owner(), with(transfer(s), "frequency", "\"WEEKLY\"", "autoClear", "true", "notes", "\"observação\"",
				"endOn", "\"" + today().plusDays(20) + "\"")).andExpect(status().isCreated()).andExpect(jsonPath("$.autoClear").value(true))
				.andExpect(jsonPath("$.notes").value("observação")));

		assertThat(JsonPath.<List<String>>read(body, "$.occurrences[?(@.amountCents < 0)].occurredOn"))
				.containsExactly(today().toString(), today().plusDays(7).toString(), today().plusDays(14).toString());
		assertThat(JsonPath.<List<Boolean>>read(body, "$.occurrences[*].autoClear")).hasSize(6).containsOnly(true);
		assertThat(JsonPath.<List<String>>read(body, "$.occurrences[*].notes")).containsOnly("observação");
	}

	@Test
	void aCreditCardCanBeTheDestination() throws Exception {
		RecurrenceScenario s = scenario();

		createRecurrence(mvc, s.owner(), with(transfer(s), "destinationAccountId", "\"" + s.card() + "\"")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.destinationAccountId").value(s.card().toString()));
	}

	@Test
	void incomeAndExpenseRecurrencesStayTheSameWithNoDestination() throws Exception {
		RecurrenceScenario s = scenario();

		String body = json(createRecurrence(mvc, s.owner(), with(valid(s))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.categoryId").value(s.rent().toString())).andExpect(jsonPath("$.destinationAccountId").doesNotExist()));

		assertThat(JsonPath.<List<Object>>read(body, "$.occurrences[*]")).hasSize(13);
		assertThat(JsonPath.<List<String>>read(body, "$.occurrences[*].type")).containsOnly("EXPENSE");
	}

	// ---- validação: nada é gravado ----------------------------------------------------------------------------

	@Test
	void theShapeIsCheckedPerTypeAndPointsAtTheField() throws Exception {
		RecurrenceScenario s = scenario();

		refuse(s, 400, "destinationAccountId", with(transfer(s), "destinationAccountId", null));
		refuse(s, 400, "destinationAccountId", with(transfer(s), "destinationAccountId", "null"));
		refuse(s, 400, "destinationAccountId", with(transfer(s), "destinationAccountId", "\"" + s.checking() + "\""));
		refuse(s, 400, "categoryId", with(transfer(s), "categoryId", "\"" + s.rent() + "\""));
		refuse(s, 400, "destinationAccountId", with(valid(s), "destinationAccountId", "\"" + s.savings() + "\""));
		refuse(s, 400, "categoryId", with(valid(s), "categoryId", null));
		refuse(s, 400, "categoryId", with(valid(s), "categoryId", "null"));
		refuse(s, 400, "destinationAccountId", with(transfer(s), "destinationAccountId", "\"nao-e-uuid\""));
	}

	@Test
	void theCommonFieldsFollowTheRulesOfTheRecurrenceOfSpec011() throws Exception {
		RecurrenceScenario s = scenario();

		for (String amount : new String[] { "0", "-5", "1000000000001", "null", "10.5" }) {
			refuse(s, 400, "amountCents", with(transfer(s), "amountCents", amount));
		}
		refuse(s, 400, "description", with(transfer(s), "description", "\"   \""));
		refuse(s, 400, "description", with(transfer(s), "description", "\"" + "x".repeat(201) + "\""));
		refuse(s, 400, "frequency", with(transfer(s), "frequency", "\"DAILY\""));
		refuse(s, 400, "startOn", with(transfer(s), "startOn", "\"" + today().minusDays(367) + "\""));
		refuse(s, 400, "startOn", with(transfer(s), "startOn", "\"2101-01-01\""));
		refuse(s, 400, "endOn", with(transfer(s), "endOn", "\"" + today().minusDays(1) + "\""));
		refuse(s, 400, null, with(transfer(s), "createdBy", "\"" + UUID.randomUUID() + "\""));
		refuse(s, 400, null, with(transfer(s), "recurrenceId", "\"" + UUID.randomUUID() + "\""));
		refuse(s, 400, null, with(transfer(s), "transferGroupId", "\"" + UUID.randomUUID() + "\""));
		refuse(s, 400, null, with(transfer(s), "status", "\"CLEARED\""));
	}

	@Test
	void anArchivedAccountIsAConflictAndAnotherWorkspacesAccountIsNotFound() throws Exception {
		RecurrenceScenario s = scenario();
		RecurrenceScenario other = scenario();
		UUID archived = new WorkspaceFixtures(owner, tokens).insertAccount(s.owner().workspaceId(), "Antiga", "CHECKING", 0, true);

		refuse(s, 409, null, with(transfer(s), "accountId", "\"" + archived + "\""));
		refuse(s, 409, null, with(transfer(s), "destinationAccountId", "\"" + archived + "\""));
		refuse(s, 404, null, with(transfer(s), "destinationAccountId", "\"" + other.savings() + "\""));
		refuse(s, 404, null, with(transfer(s), "accountId", "\"" + other.checking() + "\""));
		refuse(s, 404, null, with(transfer(s), "destinationAccountId", "\"" + UUID.randomUUID() + "\""));
		refuse(s, 404, null, with(transfer(s), "accountId", "\"" + UUID.randomUUID() + "\""));
	}

	@Test
	void aFailureInTheMiddleLeavesNeitherTheRecurrenceNorAnyLeg() throws Exception {
		RecurrenceScenario s = scenario();
		String transactions = transactionsFingerprint(owner, s.owner().workspaceId());
		String recurrences = recurrencesFingerprint(owner, s.owner().workspaceId());
		// um gatilho de teste faz a inserção das ocorrências falhar na descrição dada
		owner.client().sql("""
				create or replace function transfer_recurrence_test_boom() returns trigger language plpgsql as $f$
				begin
				  if new.description = 'EXPLODIR-NO-TESTE' then raise exception 'falha de teste'; end if;
				  return new;
				end $f$""").update();
		owner.client().sql("drop trigger if exists transfer_recurrence_test_boom on transactions").update();
		owner.client().sql("create trigger transfer_recurrence_test_boom before insert on transactions for each row "
				+ "execute function transfer_recurrence_test_boom()").update();
		try {
			assertThatThrownBy(() -> createRecurrence(mvc, s.owner(), with(transfer(s), "description", "\"EXPLODIR-NO-TESTE\"")))
					.rootCause().hasMessageContaining("falha de teste");
		}
		finally {
			owner.client().sql("drop trigger if exists transfer_recurrence_test_boom on transactions").update();
		}

		assertThat(recurrencesFingerprint(owner, s.owner().workspaceId())).isEqualTo(recurrences);
		assertThat(transactionsFingerprint(owner, s.owner().workspaceId())).isEqualTo(transactions);
	}

}
