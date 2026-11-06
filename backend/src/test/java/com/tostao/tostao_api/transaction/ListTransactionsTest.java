package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ListTransactionsTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private String base(UUID workspaceId) {
		return "/api/v1/workspaces/" + workspaceId + "/transactions";
	}

	private ResultActions list(Fixture fx, String query) throws Exception {
		return mvc.perform(get(base(fx.workspaceId()) + query).cookie(session(fx.token())));
	}

	private static LocalDate day(int d) {
		return LocalDate.of(2026, 11, d);
	}

	/** Duas contas, categorias (com subcategoria) e um conjunto variado de lançamentos. */
	private record Data(Fixture fx, UUID checking, UUID savings, UUID food, UUID market, UUID salary, UUID m1, UUID m2,
			UUID m3, UUID pay, UUID[] transfer) {
	}

	private Data data() {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID checking = fixtures().insertAccount(w, "Corrente", "CHECKING", 100_000, false);
		UUID savings = fixtures().insertAccount(w, "Poupanca", "SAVINGS", 0, false);
		UUID food = fixtures().insertCategory(w, null, "Comida", "EXPENSE", false);
		UUID market = fixtures().insertCategory(w, food, "Feira", "EXPENSE", false);
		UUID salary = fixtures().insertCategory(w, null, "Salario", "INCOME", false);
		UUID m1 = fixtures().insertLedgerTransaction(w, checking, fx.userId(), food, "EXPENSE", 1_000, day(1),
				"Mercado central", false);
		UUID m2 = fixtures().insertLedgerTransaction(w, checking, fx.userId(), market, "EXPENSE", 2_000, day(3),
				"Feira de domingo", false);
		UUID m3 = fixtures().insertLedgerTransaction(w, savings, fx.userId(), food, "EXPENSE", 3_000, day(5),
				"Desconto de 50% no Mercado", false);
		UUID pay = fixtures().insertLedgerTransaction(w, checking, fx.userId(), salary, "INCOME", 9_000, day(5),
				"Salario de novembro", false);
		UUID[] transfer = fixtures().insertTransferPair(w, checking, savings, fx.userId(), 500, day(4), "Reserva");
		return new Data(fx, checking, savings, food, market, salary, m1, m2, m3, pay, transfer);
	}

	@Test
	void listsNewestFirstWithTheTotals() throws Exception {
		Data d = data();

		list(d.fx(), "").andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalItems").value(6))
				.andExpect(jsonPath("$.totalPages").value(1))
				.andExpect(jsonPath("$.items", hasSize(6)))
				.andExpect(jsonPath("$.items[0].occurredOn").value("2026-11-05"))
				.andExpect(jsonPath("$.items[5].occurredOn").value("2026-11-01"))
				.andExpect(jsonPath("$.items[?(@.type == 'TRANSFER')].transferGroupId").exists());
	}

	@Test
	void tiesOfDateAreBrokenByTheNewestCreatedFirst() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID account = fixtures().insertAccount(w, "Corrente", "CHECKING", 0, false);
		UUID category = fixtures().insertCategory(w, null, "Comida", "EXPENSE", false);
		UUID older = fixtures().insertLedgerTransaction(w, account, fx.userId(), category, "EXPENSE", 100, day(5),
				"mais antigo", false);
		UUID newer = fixtures().insertLedgerTransaction(w, account, fx.userId(), category, "EXPENSE", 100, day(5),
				"mais novo", false);
		owner.client().sql("update transactions set created_at = now() - interval '1 hour' where id = :id")
				.param("id", older).update();

		list(fx, "").andExpect(jsonPath("$.items[*].id", contains(newer.toString(), older.toString())));
	}

	@Test
	void walkingEveryPageReturnsEachTransactionExactlyOnce() throws Exception {
		Data d = data();
		Set<String> seen = new HashSet<>();
		List<String> dates = new ArrayList<>();

		for (int page = 1; page <= 3; page++) {
			String json = list(d.fx(), "?size=2&page=" + page).andExpect(status().isOk())
					.andExpect(jsonPath("$.totalItems").value(6)).andExpect(jsonPath("$.totalPages").value(3))
					.andReturn().getResponse().getContentAsString();
			List<String> ids = JsonPath.read(json, "$.items[*].id");
			List<String> pageDates = JsonPath.read(json, "$.items[*].occurredOn");
			assertThat(ids).hasSize(2);
			assertThat(seen.addAll(ids)).isTrue();
			dates.addAll(pageDates);
		}

		assertThat(seen).hasSize(6);
		assertThat(dates).isSortedAccordingTo(java.util.Comparator.reverseOrder());
		list(d.fx(), "?size=2&page=4").andExpect(jsonPath("$.items", hasSize(0)))
				.andExpect(jsonPath("$.totalItems").value(6));
	}

	@Test
	void theDateRangeIsInclusive() throws Exception {
		Data d = data();

		list(d.fx(), "?from=2026-11-03&to=2026-11-04").andExpect(jsonPath("$.totalItems").value(3));
		list(d.fx(), "?from=2026-11-05").andExpect(jsonPath("$.totalItems").value(2));
		list(d.fx(), "?to=2026-11-01").andExpect(jsonPath("$.totalItems").value(1));
		list(d.fx(), "?from=2026-11-06").andExpect(jsonPath("$.totalItems").value(0));
		list(d.fx(), "?from=2026-11-05&to=2026-11-03").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("from"));
	}

	@Test
	void filtersByAccountIncludingTransferLegs() throws Exception {
		Data d = data();

		list(d.fx(), "?accountId=" + d.savings()).andExpect(jsonPath("$.totalItems").value(2))
				.andExpect(jsonPath("$.items[*].description", containsInAnyOrder("Desconto de 50% no Mercado", "Reserva")));
		list(d.fx(), "?accountId=" + d.checking()).andExpect(jsonPath("$.totalItems").value(4));
	}

	@Test
	void filteringByAMainCategoryIncludesItsSubcategories() throws Exception {
		Data d = data();
		UUID archivedSub = fixtures().insertCategory(d.fx().workspaceId(), d.food(), "Antiga", "EXPENSE", true);
		fixtures().insertLedgerTransaction(d.fx().workspaceId(), d.checking(), d.fx().userId(), archivedSub, "EXPENSE",
				100, day(2), "De subcategoria arquivada", false);

		list(d.fx(), "?categoryId=" + d.food()).andExpect(jsonPath("$.totalItems").value(4));
		list(d.fx(), "?categoryId=" + d.market()).andExpect(jsonPath("$.totalItems").value(1))
				.andExpect(jsonPath("$.items[0].description").value("Feira de domingo"));
		list(d.fx(), "?categoryId=" + d.salary()).andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	void filtersByType() throws Exception {
		Data d = data();

		list(d.fx(), "?type=INCOME").andExpect(jsonPath("$.totalItems").value(1));
		list(d.fx(), "?type=EXPENSE").andExpect(jsonPath("$.totalItems").value(3));
		list(d.fx(), "?type=TRANSFER").andExpect(jsonPath("$.totalItems").value(2))
				.andExpect(jsonPath("$.items[*].type", contains("TRANSFER", "TRANSFER")));
	}

	@Test
	void searchesTheDescriptionIgnoringCaseWithLiteralWildcards() throws Exception {
		Data d = data();

		list(d.fx(), "?q=mercado").andExpect(jsonPath("$.totalItems").value(2));
		list(d.fx(), "?q=MERCADO").andExpect(jsonPath("$.totalItems").value(2));
		// o texto vai como parâmetro (sem passar pelo modelo de URI, que recodificaria o %)
		search(d.fx(), "%").andExpect(jsonPath("$.totalItems").value(1))
				.andExpect(jsonPath("$.items[0].description").value("Desconto de 50% no Mercado"));
		search(d.fx(), "_").andExpect(jsonPath("$.totalItems").value(0));
		search(d.fx(), "\\").andExpect(jsonPath("$.totalItems").value(0));
		search(d.fx(), "50%").andExpect(jsonPath("$.totalItems").value(1));
		search(d.fx(), "5_%").andExpect(jsonPath("$.totalItems").value(0));
		search(d.fx(), "x".repeat(101)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("q"));
		search(d.fx(), "x".repeat(100)).andExpect(status().isOk());
	}

	private ResultActions search(Fixture fx, String text) throws Exception {
		return mvc.perform(get(base(fx.workspaceId())).param("q", text).cookie(session(fx.token())));
	}

	@Test
	void combinedFiltersReturnTheIntersection() throws Exception {
		Data d = data();

		list(d.fx(), "?accountId=" + d.checking() + "&type=EXPENSE&from=2026-11-02&to=2026-11-04&q=feira")
				.andExpect(jsonPath("$.totalItems").value(1))
				.andExpect(jsonPath("$.items[0].id").value(d.m2().toString()));
		list(d.fx(), "?accountId=" + d.savings() + "&categoryId=" + d.food() + "&q=desconto")
				.andExpect(jsonPath("$.totalItems").value(1));
		list(d.fx(), "?accountId=" + d.savings() + "&type=INCOME").andExpect(jsonPath("$.totalItems").value(0));
	}

	@Test
	void deletedTransactionsNeverAppear() throws Exception {
		Data d = data();
		UUID gone = fixtures().insertLedgerTransaction(d.fx().workspaceId(), d.checking(), d.fx().userId(), d.food(),
				"EXPENSE", 700, day(2), "Excluido", true);

		list(d.fx(), "").andExpect(jsonPath("$.totalItems").value(6));
		list(d.fx(), "?q=excluido").andExpect(jsonPath("$.totalItems").value(0));
		mvc.perform(get(base(d.fx().workspaceId()) + "/" + gone).cookie(session(d.fx().token())))
				.andExpect(status().isNotFound());
	}

	@Test
	void invalidParametersAreRejectedWithTheParameterName() throws Exception {
		Data d = data();

		for (String bad : new String[] { "page=0", "page=-1", "page=abc", "size=0", "size=101", "size=abc",
				"type=XYZ", "accountId=abc", "categoryId=abc", "from=ontem", "to=2026-13-45" }) {
			String field = bad.substring(0, bad.indexOf('='));
			list(d.fx(), "?" + bad).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value(field));
		}
	}

	@Test
	void anUnknownAccountOrCategoryInTheFilterReturnsAnEmptyPage() throws Exception {
		Data d = data();
		Fixture other = fixtures().workspaceWithOwner();
		UUID foreignAccount = fixtures().insertAccount(other.workspaceId(), "Alheia", "CHECKING", 0, false);
		UUID foreignCategory = fixtures().insertCategory(other.workspaceId(), null, "Alheia", "EXPENSE", false);

		list(d.fx(), "?accountId=" + UUID.randomUUID()).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalItems").value(0));
		list(d.fx(), "?accountId=" + foreignAccount).andExpect(jsonPath("$.totalItems").value(0));
		list(d.fx(), "?categoryId=" + foreignCategory).andExpect(jsonPath("$.totalItems").value(0));
	}

	@Test
	void anEmptyWorkspaceReturnsAnEmptyPage() throws Exception {
		Fixture fx = fixtures().workspaceWithOwner();

		list(fx, "").andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
				.andExpect(jsonPath("$.totalItems").value(0)).andExpect(jsonPath("$.totalPages").value(0));
	}

	@Test
	void getsOneTransactionAndTheOtherLegOfATransfer() throws Exception {
		Data d = data();

		mvc.perform(get(base(d.fx().workspaceId()) + "/" + d.m1()).cookie(session(d.fx().token())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(d.m1().toString()))
				.andExpect(jsonPath("$.amountCents").value(-1_000))
				.andExpect(jsonPath("$.counterpartAccountId").doesNotExist())
				.andExpect(jsonPath("$.workspaceId").doesNotExist());
		mvc.perform(get(base(d.fx().workspaceId()) + "/" + d.transfer()[1]).cookie(session(d.fx().token())))
				.andExpect(jsonPath("$.amountCents").value(500))
				.andExpect(jsonPath("$.accountId").value(d.savings().toString()))
				.andExpect(jsonPath("$.counterpartAccountId").value(d.checking().toString()));
	}

	@Test
	void anotherWorkspacesMissingOrMalformedIdsAreNotFound() throws Exception {
		Data d = data();
		Fixture other = fixtures().workspaceWithOwner();
		UUID otherAccount = fixtures().insertAccount(other.workspaceId(), "Alheia", "CHECKING", 0, false);
		UUID foreign = fixtures().insertLedgerTransaction(other.workspaceId(), otherAccount, other.userId(), null,
				"INCOME", 100, day(1), "Alheio", false);

		for (String id : new String[] { foreign.toString(), UUID.randomUUID().toString(), "abc" }) {
			mvc.perform(get(base(d.fx().workspaceId()) + "/" + id).cookie(session(d.fx().token())))
					.andExpect(status().isNotFound());
		}
	}

}
