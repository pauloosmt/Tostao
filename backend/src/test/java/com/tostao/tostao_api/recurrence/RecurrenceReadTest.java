package com.tostao.tostao_api.recurrence;

import static com.tostao.tostao_api.recurrence.RecurrenceScenario.createRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.getRecurrence;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.listRecurrences;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.recurrencesUrl;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.today;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.valid;
import static com.tostao.tostao_api.recurrence.RecurrenceScenario.with;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.tostao.tostao_api.support.WorkspaceFixtures;

/** Ler as recorrências: a lista, o detalhe com as ocorrências e a próxima ocorrência pendente. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class RecurrenceReadTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

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

	private String create(RecurrenceScenario s, String json) throws Exception {
		return body(createRecurrence(mvc, s.owner(), json).andExpect(status().isCreated()));
	}

	@Test
	void theListHasEachRecurrenceOfTheWorkspaceFromTheNewestToTheOldest() throws Exception {
		RecurrenceScenario s = scenario();
		RecurrenceScenario other = scenario();
		String first = create(s, with(valid(s), "description", "\"Primeira\""));
		String second = create(s, with(valid(s), "description", "\"Segunda\"", "frequency", "\"WEEKLY\""));
		String third = create(s, with(valid(s), "description", "\"Terceira\"", "frequency", "\"YEARLY\"", "autoClear", "true"));
		create(other, with(valid(other), "description", "\"De outro workspace\""));

		String listed = body(listRecurrences(mvc, s.owner()).andExpect(status().isOk()));

		assertThat(JsonPath.<List<String>>read(listed, "$[*].id")).containsExactly(JsonPath.read(third, "$.id"),
				JsonPath.read(second, "$.id"), JsonPath.read(first, "$.id"));
		assertThat(JsonPath.<List<String>>read(listed, "$[*].description")).containsExactly("Terceira", "Segunda", "Primeira");
		assertThat(JsonPath.<List<String>>read(listed, "$[*].frequency")).containsExactly("YEARLY", "WEEKLY", "MONTHLY");
		assertThat(JsonPath.<List<Boolean>>read(listed, "$[*].ended")).containsOnly(false);
		assertThat(JsonPath.<List<Boolean>>read(listed, "$[*].autoClear")).containsExactly(true, false, false);
		assertThat(JsonPath.<List<String>>read(listed, "$[*].nextOccurrenceOn")).containsOnly(today().toString());
		// o resumo não traz a lista de ocorrências nem os totais
		assertThat(JsonPath.<List<Object>>read(listed, "$[*].occurrences")).isEmpty();
	}

	@Test
	void anEmptyWorkspaceHasAnEmptyList() throws Exception {
		RecurrenceScenario s = scenario();

		listRecurrences(mvc, s.owner()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void theDetailListsTheLiveOccurrencesInOrderAndTheTotalsMatchTheirSum() throws Exception {
		RecurrenceScenario s = scenario();
		String created = create(s, with(valid(s)));
		String id = JsonPath.read(created, "$.id");
		List<String> ids = JsonPath.read(created, "$.occurrences[*].id");

		clear(mvc, s.owner(), UUID.fromString(ids.get(0))).andExpect(status().isOk());
		clear(mvc, s.owner(), UUID.fromString(ids.get(1))).andExpect(status().isOk());
		mvc.perform(delete(transactionsUrl(s.owner().workspaceId()) + "/" + ids.get(5)).cookie(session(s.owner().token())))
				.andExpect(status().isNoContent());

		String detail = body(getRecurrence(mvc, s.owner(), id).andExpect(status().isOk()));
		assertThat(JsonPath.<List<Integer>>read(detail, "$.occurrences[*].occurrenceNumber"))
				.containsExactly(0, 1, 2, 3, 4, 6, 7, 8, 9, 10, 11, 12);
		// 12 ocorrências de 150000: 2 efetivadas e 10 pendentes
		assertThat(((Number) JsonPath.read(detail, "$.totalCents")).longValue()).isEqualTo(12 * 150_000L);
		assertThat(((Number) JsonPath.read(detail, "$.clearedCents")).longValue()).isEqualTo(2 * 150_000L);
		assertThat(((Number) JsonPath.read(detail, "$.pendingCents")).longValue()).isEqualTo(10 * 150_000L);
		long summed = JsonPath.<List<Number>>read(detail, "$.occurrences[*].amountCents").stream()
				.mapToLong(n -> Math.abs(n.longValue())).sum();
		assertThat(summed).isEqualTo(((Number) JsonPath.read(detail, "$.totalCents")).longValue());
	}

	@Test
	void theNextOccurrenceIsThePendingOneFromTodayOnAndNeverAnOverdueOne() throws Exception {
		RecurrenceScenario s = scenario();
		// início 40 dias atrás: as ocorrências 0 e 1 já passaram (pendentes e vencidas); a 2 é a próxima
		String created = create(s, with(valid(s), "startOn", "\"" + today().minusDays(40) + "\""));
		List<String> dates = JsonPath.read(created, "$.occurrences[*].occurredOn");
		String firstFromToday = dates.stream().filter(d -> !java.time.LocalDate.parse(d).isBefore(today())).findFirst().orElseThrow();

		assertThat((String) JsonPath.read(created, "$.nextOccurrenceOn")).isEqualTo(firstFromToday);
		assertThat(java.time.LocalDate.parse(firstFromToday)).isAfterOrEqualTo(today());
		assertThat(dates.get(0)).isEqualTo(today().minusDays(40).toString());
		// a lista traz o mesmo valor
		assertThat(JsonPath.<List<String>>read(body(listRecurrences(mvc, s.owner())), "$[*].nextOccurrenceOn"))
				.containsExactly(firstFromToday);
	}

	@Test
	void withNoPendingOccurrenceFromTodayOnTheNextOneIsNull() throws Exception {
		RecurrenceScenario s = scenario();
		// com fim cinco dias atrás, só restam ocorrências vencidas
		String created = create(s, with(valid(s), "startOn", "\"" + today().minusDays(40) + "\"", "endOn",
				"\"" + today().minusDays(5) + "\""));

		assertThat(JsonPath.<List<String>>read(created, "$.occurrences[*].status")).containsOnly("PENDING");
		assertThat(JsonPath.<Object>read(created, "$.nextOccurrenceOn")).isNull();
		getRecurrence(mvc, s.owner(), JsonPath.<String>read(created, "$.id")).andExpect(status().isOk())
				.andExpect(jsonPath("$.nextOccurrenceOn").doesNotExist());
	}

	@Test
	void clearingTheOccurrencesMovesTheNextOneForward() throws Exception {
		RecurrenceScenario s = scenario();
		String created = create(s, with(valid(s)));
		String id = JsonPath.read(created, "$.id");
		List<String> ids = JsonPath.read(created, "$.occurrences[*].id");
		List<String> dates = JsonPath.read(created, "$.occurrences[*].occurredOn");

		clear(mvc, s.owner(), UUID.fromString(ids.get(0))).andExpect(status().isOk());

		getRecurrence(mvc, s.owner(), id).andExpect(status().isOk()).andExpect(jsonPath("$.nextOccurrenceOn").value(dates.get(1)));
	}

	@Test
	void anUnknownMalformedOrForeignRecurrenceIsNotFound() throws Exception {
		RecurrenceScenario mine = scenario();
		RecurrenceScenario theirs = scenario();
		String foreign = JsonPath.read(create(theirs, with(valid(theirs))), "$.id");

		getRecurrence(mvc, mine.owner(), UUID.randomUUID()).andExpect(status().isNotFound());
		getRecurrence(mvc, mine.owner(), "abc").andExpect(status().isNotFound());
		getRecurrence(mvc, mine.owner(), foreign).andExpect(status().isNotFound());
		getRecurrence(mvc, theirs.owner(), foreign).andExpect(status().isOk());
	}

	@Test
	void withoutASessionTheAnswerIsUnauthorized() throws Exception {
		RecurrenceScenario s = scenario();
		String id = JsonPath.read(create(s, with(valid(s))), "$.id");

		mvc.perform(get(recurrencesUrl(s.owner().workspaceId()))).andExpect(status().isUnauthorized());
		mvc.perform(get(recurrencesUrl(s.owner().workspaceId()) + "/" + id)).andExpect(status().isUnauthorized());
	}

	@Test
	void theReadResponsesAreNeverCached() throws Exception {
		RecurrenceScenario s = scenario();
		String id = JsonPath.read(create(s, with(valid(s))), "$.id");

		listRecurrences(mvc, s.owner()).andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")));
		getRecurrence(mvc, s.owner(), id).andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")));
	}

}
