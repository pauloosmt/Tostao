package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.InstallmentScenario.plan;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchasesUrl;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static com.tostao.tostao_api.transaction.PendingScenario.edit;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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

/** História 2: ver o plano de parcelas, com o que já foi efetivado e o que falta. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InstallmentPlanTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	/** Um plano de 3 parcelas de 100000 (a 1ª efetivada, as outras pendentes), gravadas fora de ordem. */
	private record Notebook(InstallmentScenario s, UUID group, UUID first, UUID second, UUID third) {
	}

	private Notebook notebook() {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID user = s.owner().userId();
		UUID group = UUID.randomUUID();
		UUID third = fixtures().insertInstallment(w, s.checking(), user, s.gadgets(), group, 3, 3, 100_000,
				LocalDate.of(2027, 1, 10), "Notebook (3/3)", "PENDING", false);
		UUID first = fixtures().insertInstallment(w, s.checking(), user, s.gadgets(), group, 1, 3, 100_000,
				LocalDate.of(2026, 11, 10), "Notebook (1/3)", "CLEARED", false);
		UUID second = fixtures().insertInstallment(w, s.checking(), user, s.gadgets(), group, 2, 3, 100_000,
				LocalDate.of(2026, 12, 10), "Notebook (2/3)", "PENDING", false);
		return new Notebook(s, group, first, second, third);
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private static long number(String json, String path) {
		return ((Number) JsonPath.read(json, path)).longValue();
	}

	private void deleteTransaction(InstallmentScenario s, UUID id) throws Exception {
		mvc.perform(delete(transactionsUrl(s.owner().workspaceId()) + "/" + id).cookie(session(s.owner().token())))
				.andExpect(status().isNoContent());
	}

	@Test
	void thePlanShowsTheHeaderTheTotalsAndTheInstallmentsInOrder() throws Exception {
		Notebook n = notebook();

		String json = body(plan(mvc, n.s().owner(), n.group()).andExpect(status().isOk())
				.andExpect(jsonPath("$.groupId").value(n.group().toString()))
				.andExpect(jsonPath("$.description").value("Notebook"))
				.andExpect(jsonPath("$.installmentCount").value(3))
				.andExpect(jsonPath("$.accountId").value(n.s().checking().toString()))
				.andExpect(jsonPath("$.categoryId").value(n.s().gadgets().toString()))
				.andExpect(jsonPath("$.totalCents").value(300_000))
				.andExpect(jsonPath("$.clearedCents").value(100_000))
				.andExpect(jsonPath("$.pendingCents").value(200_000)));

		assertThat(JsonPath.<List<Integer>>read(json, "$.installments[*].installmentNumber")).containsExactly(1, 2, 3);
		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].id"))
				.containsExactly(n.first().toString(), n.second().toString(), n.third().toString());
		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].status"))
				.containsExactly("CLEARED", "PENDING", "PENDING");
		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].description"))
				.containsExactly("Notebook (1/3)", "Notebook (2/3)", "Notebook (3/3)");
		assertThat(JsonPath.<List<Integer>>read(json, "$.installments[*].amountCents"))
				.containsExactly(-100_000, -100_000, -100_000);
	}

	@Test
	void clearingAnInstallmentMovesItFromPendingToCleared() throws Exception {
		Notebook n = notebook();

		clear(mvc, n.s().owner(), n.second()).andExpect(status().isOk());

		plan(mvc, n.s().owner(), n.group()).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCents").value(300_000))
				.andExpect(jsonPath("$.clearedCents").value(200_000))
				.andExpect(jsonPath("$.pendingCents").value(100_000));
	}

	@Test
	void anExcludedInstallmentLeavesTheListAndTheTotalsButTheCountStaysTheOriginal() throws Exception {
		Notebook n = notebook();

		deleteTransaction(n.s(), n.third());

		String json = body(plan(mvc, n.s().owner(), n.group()).andExpect(status().isOk())
				.andExpect(jsonPath("$.installmentCount").value(3))
				.andExpect(jsonPath("$.totalCents").value(200_000))
				.andExpect(jsonPath("$.clearedCents").value(100_000))
				.andExpect(jsonPath("$.pendingCents").value(100_000)));
		assertThat(JsonPath.<List<Integer>>read(json, "$.installments[*].installmentNumber")).containsExactly(1, 2);
	}

	@Test
	void aPlanWithEveryInstallmentExcludedStillExistsWithAnEmptyList() throws Exception {
		Notebook n = notebook();

		deleteTransaction(n.s(), n.first());
		deleteTransaction(n.s(), n.second());
		deleteTransaction(n.s(), n.third());

		plan(mvc, n.s().owner(), n.group()).andExpect(status().isOk())
				.andExpect(jsonPath("$.groupId").value(n.group().toString()))
				.andExpect(jsonPath("$.description").value("Notebook"))
				.andExpect(jsonPath("$.installmentCount").value(3))
				.andExpect(jsonPath("$.accountId").value(n.s().checking().toString()))
				.andExpect(jsonPath("$.categoryId").value(n.s().gadgets().toString()))
				.andExpect(jsonPath("$.totalCents").value(0))
				.andExpect(jsonPath("$.clearedCents").value(0))
				.andExpect(jsonPath("$.pendingCents").value(0))
				.andExpect(jsonPath("$.installments").isEmpty());
	}

	@Test
	void theHeaderStaysStableWhenTheFirstInstallmentIsExcluded() throws Exception {
		Notebook n = notebook();

		deleteTransaction(n.s(), n.first());

		plan(mvc, n.s().owner(), n.group()).andExpect(status().isOk())
				.andExpect(jsonPath("$.description").value("Notebook"))
				.andExpect(jsonPath("$.installmentCount").value(3))
				.andExpect(jsonPath("$.installments.length()").value(2))
				.andExpect(jsonPath("$.totalCents").value(200_000))
				.andExpect(jsonPath("$.clearedCents").value(0))
				.andExpect(jsonPath("$.pendingCents").value(200_000));
	}

	@Test
	void anEditedDescriptionWithoutTheSuffixIsReturnedAsIs() throws Exception {
		Notebook n = notebook();
		String edited = "{\"accountId\":\"%s\",\"amountCents\":100000,\"occurredOn\":\"2026-11-10\",\"description\":\"Notebook do trabalho\",\"categoryId\":\"%s\"}"
				.formatted(n.s().checking(), n.s().gadgets());

		edit(mvc, n.s().owner(), n.first(), edited).andExpect(status().isOk());

		plan(mvc, n.s().owner(), n.group()).andExpect(status().isOk())
				.andExpect(jsonPath("$.description").value("Notebook do trabalho"));
	}

	@Test
	void theTotalsFollowAnEditedAmount() throws Exception {
		Notebook n = notebook();
		String edited = "{\"accountId\":\"%s\",\"amountCents\":120000,\"occurredOn\":\"2026-12-10\",\"description\":\"Notebook (2/3)\",\"categoryId\":\"%s\"}"
				.formatted(n.s().checking(), n.s().gadgets());

		edit(mvc, n.s().owner(), n.second(), edited).andExpect(status().isOk());

		plan(mvc, n.s().owner(), n.group()).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCents").value(320_000))
				.andExpect(jsonPath("$.clearedCents").value(100_000))
				.andExpect(jsonPath("$.pendingCents").value(220_000));
	}

	@Test
	void anUnknownOrMalformedOrForeignGroupIsNotFound() throws Exception {
		Notebook mine = notebook();
		Notebook theirs = notebook();

		plan(mvc, mine.s().owner(), UUID.randomUUID()).andExpect(status().isNotFound());
		plan(mvc, mine.s().owner(), "abc").andExpect(status().isNotFound());
		// o grupo existe, mas em outro workspace
		plan(mvc, mine.s().owner(), theirs.group()).andExpect(status().isNotFound());
		// e o workspace dela com o grupo dela continua funcionando
		plan(mvc, theirs.s().owner(), theirs.group()).andExpect(status().isOk());
	}

	@Test
	void withoutASessionTheAnswerIsUnauthorized() throws Exception {
		Notebook n = notebook();

		mvc.perform(get(purchasesUrl(n.s().owner().workspaceId()) + "/" + n.group()))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void theResponseIsNeverCached() throws Exception {
		Notebook n = notebook();

		plan(mvc, n.s().owner(), n.group()).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")));
	}

	@Test
	void anOrdinaryExpenseIdIsNotAPlan() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		UUID ordinary = fixtures().insertLedgerTransaction(s.owner().workspaceId(), s.checking(), s.owner().userId(),
				s.food(), "EXPENSE", 500, LocalDate.of(2026, 11, 1), "Comum", false);

		plan(mvc, s.owner(), ordinary).andExpect(status().isNotFound());
	}

}
