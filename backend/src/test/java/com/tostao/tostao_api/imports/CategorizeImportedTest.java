package com.tostao.tostao_api.imports;

import static com.tostao.tostao_api.imports.ImportScenario.body;
import static com.tostao.tostao_api.imports.ImportScenario.confirm;
import static com.tostao.tostao_api.imports.ImportScenario.line;
import static com.tostao.tostao_api.imports.ImportScenario.preview;
import static com.tostao.tostao_api.imports.ImportScenario.seed;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * História 3: categorizar na importação ou depois (FR-011, FR-014, FR-017, FR-018). A sugestão vira categoria na
 * confirmação, o que ficar sem categoria é achado por {@code uncategorized=true} e a edição da spec 004 o categoriza.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CategorizeImportedTest {

	private static final LocalDate NOV_6 = LocalDate.of(2026, 11, 6);

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private ImportScenario scenario() {
		return ImportScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	private ResultActions list(Fixture who, String query) throws Exception {
		return mvc.perform(get("/api/v1/workspaces/" + who.workspaceId() + "/transactions?size=100&" + query)
				.cookie(session(who.token())));
	}

	private List<String> ids(Fixture who, String query) throws Exception {
		return JsonPath.read(list(who, query).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.items[*].id");
	}

	/** Semeia um conjunto misto: com e sem categoria, excluída, pendente, de outra conta e uma transferência. */
	private record Mix(UUID withCategory, UUID noCategoryExpense, UUID noCategoryIncome, UUID deleted, UUID pending,
			UUID otherAccount, UUID transferOut, UUID transferIn) {
	}

	private Mix mix(ImportScenario s) {
		UUID w = s.workspace();
		UUID user = s.owner().userId();
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		UUID withCategory = seed(owner, w, s.checking(), user, s.food(), -1000, NOV_6, "Com categoria", null, false);
		UUID noCategoryExpense = seed(owner, w, s.checking(), user, null, -2000, NOV_6, "Despesa sem categoria", "id:1", false);
		UUID noCategoryIncome = seed(owner, w, s.checking(), user, null, 3000, LocalDate.of(2026, 11, 7), "Receita sem categoria", "id:2", false);
		UUID deleted = seed(owner, w, s.checking(), user, null, -4000, NOV_6, "Excluída sem categoria", "id:3", true);
		UUID pending = owner.client().sql("""
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description, created_by, status)
				values (:w, :a, 'EXPENSE', -5000, date '2026-11-08', 'Pendente sem categoria', :u, 'PENDING') returning id""")
				.param("w", w).param("a", s.checking()).param("u", user).query(UUID.class).single();
		UUID otherAccount = seed(owner, w, s.savings(), user, null, -6000, NOV_6, "Poupança sem categoria", null, false);
		UUID[] transfer = f.insertTransferPair(w, s.checking(), s.savings(), user, 7000, NOV_6, "Transferência");
		return new Mix(withCategory, noCategoryExpense, noCategoryIncome, deleted, pending, otherAccount, transfer[0], transfer[1]);
	}

	// ---- o filtro ----------------------------------------------------------------------------------------------

	@Test
	void theFilterReturnsExactlyTheIncomeAndExpenseWithoutCategory() throws Exception {
		ImportScenario s = scenario();
		Mix m = mix(s);

		List<String> found = ids(s.owner(), "uncategorized=true");

		assertThat(found).containsExactlyInAnyOrder(m.noCategoryExpense().toString(), m.noCategoryIncome().toString(),
				m.pending().toString(), m.otherAccount().toString());
		assertThat(found).doesNotContain(m.withCategory().toString(), m.deleted().toString(), m.transferOut().toString(),
				m.transferIn().toString());
	}

	@Test
	void falseAndAbsentGiveTheSameFullListing() throws Exception {
		ImportScenario s = scenario();
		mix(s);

		assertThat(ids(s.owner(), "uncategorized=false")).containsExactlyInAnyOrderElementsOf(ids(s.owner(), "page=1"));
		assertThat(ids(s.owner(), "uncategorized=false")).hasSize(7);
	}

	@Test
	void itCombinesWithAccountDatesTypeStatusAndSearch() throws Exception {
		ImportScenario s = scenario();
		Mix m = mix(s);

		assertThat(ids(s.owner(), "uncategorized=true&accountId=" + s.savings())).containsExactly(m.otherAccount().toString());
		assertThat(ids(s.owner(), "uncategorized=true&type=INCOME")).containsExactly(m.noCategoryIncome().toString());
		assertThat(ids(s.owner(), "uncategorized=true&type=EXPENSE")).containsExactlyInAnyOrder(m.noCategoryExpense().toString(),
				m.pending().toString(), m.otherAccount().toString());
		assertThat(ids(s.owner(), "uncategorized=true&status=PENDING")).containsExactly(m.pending().toString());
		assertThat(ids(s.owner(), "uncategorized=true&from=2026-11-07&to=2026-11-07")).containsExactly(m.noCategoryIncome().toString());
		assertThat(ids(s.owner(), "uncategorized=true&q=poupan")).containsExactly(m.otherAccount().toString());
		assertThat(ids(s.owner(), "uncategorized=true&q=nada-assim")).isEmpty();
	}

	@Test
	void itIsPagedLikeAnyListing() throws Exception {
		ImportScenario s = scenario();
		mix(s);

		mvc.perform(get("/api/v1/workspaces/" + s.workspace() + "/transactions?uncategorized=true&size=2&page=2")
				.cookie(session(s.owner().token()))).andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(4))
				.andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.items.length()").value(2));
	}

	@Test
	void togetherWithACategoryItIsRefusedAndOtherValuesToo() throws Exception {
		ImportScenario s = scenario();

		list(s.owner(), "uncategorized=true&categoryId=" + s.food()).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("uncategorized"));
		for (String bad : new String[] { "talvez", "TRUE", "True", "1", "0", "yes", "", " true" }) {
			list(s.owner(), "uncategorized=" + bad.replace(" ", "%20")).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("uncategorized"));
		}
		// com false a categoria pode vir junto
		list(s.owner(), "uncategorized=false&categoryId=" + s.food()).andExpect(status().isOk());
	}

	@Test
	void theFilterNeverShowsAnotherWorkspacesRows() throws Exception {
		ImportScenario mine = scenario();
		ImportScenario theirs = scenario();
		Mix others = mix(theirs);
		Mix own = mix(mine);

		List<String> found = ids(mine.owner(), "uncategorized=true");

		assertThat(found).contains(own.noCategoryExpense().toString()).doesNotContain(others.noCategoryExpense().toString());
	}

	@Test
	void aViewerCanUseTheFilter() throws Exception {
		ImportScenario s = scenario();
		Mix m = mix(s);

		assertThat(ids(s.viewer(), "uncategorized=true")).contains(m.noCategoryExpense().toString());
	}

	// ---- da sugestão à categoria -------------------------------------------------------------------------------

	@Test
	void theSuggestedCategoryBecomesTheCategoryOfTheCreatedTransaction() throws Exception {
		ImportScenario s = scenario();
		seed(owner, s.workspace(), s.checking(), s.owner().userId(), s.food(), -1000, LocalDate.of(2026, 10, 1), "Padaria do Zé", null, false);
		String preview = preview(mvc, s.owner(), s.workspace(), s.checking(), "banco.ofx").andReturn().getResponse().getContentAsString();
		String suggested = JsonPath.read(preview, "$.lines[1].suggestedCategoryId");

		String response = confirm(mvc, s.owner(), s.workspace(), s.checking(), body(
				line("id:2", "2026-11-06", "PADARIA DO ZE", -2350, UUID.fromString(suggested)),
				line("id:3", "2026-11-07", "MERCADO CENTRAL", -18090, null)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

		assertThat(suggested).isEqualTo(s.food().toString());
		assertThat((String) JsonPath.read(response, "$.created[0].categoryId")).isEqualTo(s.food().toString());
		assertThat((Object) JsonPath.read(response, "$.created[1].categoryId")).isNull();
		String uncategorizedId = JsonPath.read(response, "$.created[1].id");
		assertThat(ids(s.owner(), "uncategorized=true&accountId=" + s.checking())).containsExactly(uncategorizedId);
	}

	@Test
	void editingAnImportedTransactionToPickACategoryTakesItOutOfTheFilterAndKeepsItImported() throws Exception {
		ImportScenario s = scenario();
		String response = confirm(mvc, s.owner(), s.workspace(), s.checking(), body(
				line("a", "2026-11-05", "Sem categoria", -2500, null), line("b", "2026-11-06", "Outra", -100, null)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		String id = JsonPath.read(response, "$.created[0].id");
		assertThat(ids(s.owner(), "uncategorized=true")).hasSize(2);

		mvc.perform(put("/api/v1/workspaces/" + s.workspace() + "/transactions/" + id).cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"accountId\":\"%s\",\"amountCents\":2500,\"occurredOn\":\"2026-11-05\",\"description\":\"Sem categoria\",\"categoryId\":\"%s\"}"
						.formatted(s.checking(), s.food())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.categoryId").value(s.food().toString()))
				.andExpect(jsonPath("$.imported").value(true)).andExpect(jsonPath("$.amountCents").value(-2500));

		assertThat(ids(s.owner(), "uncategorized=true")).hasSize(1).doesNotContain(id);
		assertThat(owner.client().sql("select import_key from transactions where id = cast(:id as uuid)").param("id", id)
				.query(String.class).single()).isEqualTo("a");
	}

}
