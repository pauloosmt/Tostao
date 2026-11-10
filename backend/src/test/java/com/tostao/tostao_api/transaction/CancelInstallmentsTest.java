package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.InstallmentScenario.cancel;
import static com.tostao.tostao_api.transaction.InstallmentScenario.plan;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchase;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchaseBody;
import static com.tostao.tostao_api.transaction.PendingScenario.account;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static com.tostao.tostao_api.transaction.PendingScenario.dashboard;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsFingerprint;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/** História 3: cancelar o que ainda falta, sem tocar no que já foi efetivado. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CancelInstallmentsTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private record Bought(UUID group, List<UUID> ids) {
	}

	/** Registra pela API uma compra de {@code count} parcelas e devolve o grupo e os ids em ordem de número. */
	private Bought buy(InstallmentScenario s, UUID account, UUID category, long total, int count, String firstDate)
			throws Exception {
		String json = body(purchase(mvc, s.owner(), purchaseBody(account, category, total, count, firstDate, "Compra",
				null)).andExpect(status().isCreated()));
		List<String> ids = JsonPath.read(json, "$.installments[*].id");
		return new Bought(UUID.fromString(JsonPath.read(json, "$.groupId")), ids.stream().map(UUID::fromString).toList());
	}

	private long cancelled(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(body(result.andExpect(status().isOk())), "$.cancelled")).longValue();
	}

	private Object deletedAt(UUID id) {
		return owner.client().sql("select deleted_at from transactions where id = :id").param("id", id)
				.query((rs, i) -> rs.getTimestamp(1)).list().get(0); // pode ser nulo: single() não aceita
	}

	private Object updatedAt(UUID id) {
		return owner.client().sql("select updated_at from transactions where id = :id").param("id", id)
				.query((rs, i) -> rs.getTimestamp(1)).single();
	}

	@Test
	void cancellingExcludesOnlyThePendingInstallmentsAndKeepsTheCleared() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		Bought b = buy(s, s.checking(), s.gadgets(), 300_000, 3, "2026-11-10");

		assertThat(cancelled(cancel(mvc, s.owner(), b.group()))).isEqualTo(2);

		assertThat(deletedAt(b.ids().get(0))).as("a efetivada continua").isNull();
		// a linha continua no banco: a exclusão é lógica
		assertThat(deletedAt(b.ids().get(1))).isNotNull();
		assertThat(deletedAt(b.ids().get(2))).isNotNull();
		plan(mvc, s.owner(), b.group()).andExpect(status().isOk())
				.andExpect(jsonPath("$.installments.length()").value(1))
				.andExpect(jsonPath("$.installmentCount").value(3))
				.andExpect(jsonPath("$.totalCents").value(100_000))
				.andExpect(jsonPath("$.clearedCents").value(100_000))
				.andExpect(jsonPath("$.pendingCents").value(0));
	}

	@Test
	void cancellingAgainDoesNothing() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		Bought b = buy(s, s.checking(), s.gadgets(), 300_000, 3, "2026-11-10");
		cancel(mvc, s.owner(), b.group()).andExpect(status().isOk());
		String before = transactionsFingerprint(owner, s.owner().workspaceId());

		assertThat(cancelled(cancel(mvc, s.owner(), b.group()))).isZero();
		assertThat(cancelled(cancel(mvc, s.owner(), b.group()))).isZero();

		assertThat(transactionsFingerprint(owner, s.owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void aPlanWithNoPendingInstallmentDoesNothing() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		Bought b = buy(s, s.checking(), s.gadgets(), 300_000, 3, "2026-11-10");
		clear(mvc, s.owner(), b.ids().get(1)).andExpect(status().isOk());
		clear(mvc, s.owner(), b.ids().get(2)).andExpect(status().isOk());
		String before = transactionsFingerprint(owner, s.owner().workspaceId());

		assertThat(cancelled(cancel(mvc, s.owner(), b.group()))).isZero();

		assertThat(transactionsFingerprint(owner, s.owner().workspaceId())).isEqualTo(before);
		plan(mvc, s.owner(), b.group()).andExpect(jsonPath("$.installments.length()").value(3))
				.andExpect(jsonPath("$.clearedCents").value(300_000));
	}

	@Test
	void onlyThePendingInstallmentsThatStillExistAreCounted() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		Bought b = buy(s, s.checking(), s.gadgets(), 400_000, 4, "2026-11-10");
		// a 3ª é excluída à mão antes do cancelamento
		mvc.perform(delete(transactionsUrl(s.owner().workspaceId()) + "/" + b.ids().get(2))
				.cookie(session(s.owner().token()))).andExpect(status().isNoContent());
		Object deletedByHand = deletedAt(b.ids().get(2));

		assertThat(cancelled(cancel(mvc, s.owner(), b.group()))).isEqualTo(2);

		// a que já estava excluída não é tocada de novo
		assertThat(deletedAt(b.ids().get(2))).isEqualTo(deletedByHand);
		assertThat(deletedAt(b.ids().get(1))).isNotNull();
		assertThat(deletedAt(b.ids().get(3))).isNotNull();
	}

	@Test
	void aPlanWithEveryInstallmentExcludedStillRespondsWithZero() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		Bought b = buy(s, s.checking(), s.gadgets(), 300_000, 3, "2026-11-10");
		for (UUID id : b.ids()) {
			mvc.perform(delete(transactionsUrl(s.owner().workspaceId()) + "/" + id).cookie(session(s.owner().token())))
					.andExpect(status().isNoContent());
		}

		assertThat(cancelled(cancel(mvc, s.owner(), b.group()))).isZero();
	}

	@Test
	void cancellingChangesNoRealizedNumberAndDropsTheForecast() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		Bought notebook = buy(s, s.checking(), s.gadgets(), 300_000, 3, "2026-11-10");
		Bought phone = buy(s, s.card(), s.food(), 9_000, 3, "2026-11-05");
		clear(mvc, s.owner(), phone.ids().get(1)).andExpect(status().isOk());
		mvc.perform(put("/api/v1/workspaces/" + w + "/monthly-budgets").cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"categoryId\":\"%s\",\"month\":\"2026-11\",\"amountCents\":500000}".formatted(s.food())))
				.andExpect(status().is2xxSuccessful());

		String accountBefore = body(account(mvc, s.owner(), s.checking()));
		String dashboardBefore = body(dashboard(mvc, s.owner(), "month=2026-11"));
		String dashboardDecBefore = body(dashboard(mvc, s.owner(), "month=2026-12"));
		String budgetBefore = body(read(w, "/monthly-budgets?month=2026-11", s));
		List<String> invoicesBefore = invoices(s, w);
		assertThat(JsonPath.<Number>read(accountBefore, "$.balanceCents").longValue()).isEqualTo(400_000);
		assertThat(JsonPath.<Number>read(accountBefore, "$.pendingCents").longValue()).isEqualTo(-200_000);

		assertThat(cancelled(cancel(mvc, s.owner(), notebook.group()))).isEqualTo(2);
		assertThat(cancelled(cancel(mvc, s.owner(), phone.group()))).isEqualTo(1);

		String accountAfter = body(account(mvc, s.owner(), s.checking()));
		// o realizado não muda...
		assertThat(JsonPath.<Number>read(accountAfter, "$.balanceCents").longValue()).isEqualTo(400_000);
		assertThat(body(dashboard(mvc, s.owner(), "month=2026-11"))).isEqualTo(dashboardBefore);
		assertThat(JsonPath.<Object>read(body(dashboard(mvc, s.owner(), "month=2026-12")), "$.totals"))
				.isEqualTo(JsonPath.<Object>read(dashboardDecBefore, "$.totals"));
		assertThat(body(read(w, "/monthly-budgets?month=2026-11", s))).isEqualTo(budgetBefore);
		assertThat(invoices(s, w)).isEqualTo(invoicesBefore);
		// ...e o previsto deixa de incluir as canceladas
		assertThat(JsonPath.<Number>read(accountAfter, "$.pendingCents").longValue()).isZero();
		assertThat(JsonPath.<Number>read(accountAfter, "$.projectedBalanceCents").longValue()).isEqualTo(400_000);
	}

	/** O total de cada fatura do cartão de novembro de 2026 a janeiro de 2027. */
	private List<String> invoices(InstallmentScenario s, UUID w) throws Exception {
		return List.of("2026-11", "2026-12", "2027-01").stream().map(month -> {
			try {
				return body(mvc.perform(get("/api/v1/workspaces/" + w + "/accounts/" + s.card() + "/invoices/" + month)
						.cookie(session(s.owner().token()))).andExpect(status().isOk()));
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}).toList();
	}

	private ResultActions read(UUID w, String path, InstallmentScenario s) throws Exception {
		return mvc.perform(get("/api/v1/workspaces/" + w + path).cookie(session(s.owner().token())))
				.andExpect(status().isOk());
	}

	@Test
	void theClearedInstallmentIsNeverTouched() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		Bought b = buy(s, s.checking(), s.gadgets(), 300_000, 3, "2026-11-10");
		Object updatedBefore = updatedAt(b.ids().get(0));
		String clearedRowBefore = owner.client().sql("select x::text from transactions x where id = :id")
				.param("id", b.ids().get(0)).query(String.class).single();

		cancel(mvc, s.owner(), b.group()).andExpect(status().isOk());

		assertThat(updatedAt(b.ids().get(0))).isEqualTo(updatedBefore);
		assertThat(owner.client().sql("select x::text from transactions x where id = :id").param("id", b.ids().get(0))
				.query(String.class).single()).isEqualTo(clearedRowBefore);
	}

	@Test
	void anUnknownMalformedOrForeignPlanIsNotFoundAndNothingChanges() throws Exception {
		InstallmentScenario mine = InstallmentScenario.create(fixtures());
		InstallmentScenario theirs = InstallmentScenario.create(fixtures());
		Bought other = buy(theirs, theirs.checking(), theirs.gadgets(), 300_000, 3, "2026-11-10");
		String myBefore = transactionsFingerprint(owner, mine.owner().workspaceId());
		String theirBefore = transactionsFingerprint(owner, theirs.owner().workspaceId());

		cancel(mvc, mine.owner(), UUID.randomUUID()).andExpect(status().isNotFound());
		cancel(mvc, mine.owner(), "abc").andExpect(status().isNotFound());
		cancel(mvc, mine.owner(), other.group()).andExpect(status().isNotFound());

		assertThat(transactionsFingerprint(owner, mine.owner().workspaceId())).isEqualTo(myBefore);
		assertThat(transactionsFingerprint(owner, theirs.owner().workspaceId())).isEqualTo(theirBefore);
	}

	@Test
	void theResponseIsNeverCached() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		Bought b = buy(s, s.checking(), s.gadgets(), 300_000, 3, "2026-11-10");

		cancel(mvc, s.owner(), b.group()).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")));
	}

	@Test
	void anOrdinaryExpenseIsNotAPlanToCancel() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		UUID ordinary = fixtures().insertLedgerTransaction(s.owner().workspaceId(), s.checking(), s.owner().userId(),
				s.food(), "EXPENSE", 500, LocalDate.of(2026, 11, 1), "Comum", false, "PENDING");

		cancel(mvc, s.owner(), ordinary).andExpect(status().isNotFound());
		assertThat(deletedAt(ordinary)).isNull();
	}

}
