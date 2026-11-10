package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.transaction.PendingScenario.body;
import static com.tostao.tostao_api.transaction.PendingScenario.create;
import static com.tostao.tostao_api.transaction.PendingScenario.list;
import static com.tostao.tostao_api.transaction.PendingScenario.transferBody;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsUrl;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/**
 * FR-019: toda resposta de lançamento diz se ele veio de uma importação de extrato ({@code imported}), sem expor a chave
 * e sem mudar nenhum campo existente. Os lançamentos que já existiam, de qualquer origem, respondem {@code false}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ImportedFlagTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private PendingScenario scenario() {
		return PendingScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	private String jsonOf(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	@Test
	void everyTransactionCreatedByTheApiIsNotImported() throws Exception {
		PendingScenario s = scenario();

		create(mvc, s.owner(), body("EXPENSE", s.checking(), 5_000, "2026-11-10", "Mercado", s.food(), null))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.imported").value(false));
		create(mvc, s.owner(), body("INCOME", s.checking(), 9_000, "2026-11-10", "Salário", s.salary(), "PENDING"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.imported").value(false));
		create(mvc, s.owner(), transferBody(s.checking(), s.savings(), 1_000, "2026-11-10", "Reserva", null))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.imported").value(false));
	}

	@Test
	void theListingAndTheDetailCarryTheFlagForEveryKindOfRow() throws Exception {
		PendingScenario s = scenario();
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		UUID workspace = s.owner().workspaceId();
		UUID user = s.owner().userId();
		UUID ordinary = f.insertLedgerTransaction(workspace, s.checking(), user, s.food(), "EXPENSE", 1_000,
				java.time.LocalDate.of(2026, 11, 1), "Comum", false);
		UUID imported = owner.client().sql("""
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description, created_by,
				                          import_key)
				values (:w, :a, 'EXPENSE', -2000, date '2026-11-02', 'Importada sem categoria', :u, 'id:abc')
				returning id""").param("w", workspace).param("a", s.checking()).param("u", user).query(UUID.class).single();
		UUID[] transfer = f.insertTransferPair(workspace, s.checking(), s.savings(), user, 3_000,
				java.time.LocalDate.of(2026, 11, 3), "Transferência");
		UUID group = UUID.randomUUID();
		UUID parcel = f.insertInstallment(workspace, s.checking(), user, s.food(), group, 1, 3, 4_000,
				java.time.LocalDate.of(2026, 11, 4), "Parcela", "CLEARED", false);

		String page = jsonOf(list(mvc, s.owner(), "size=100").andExpect(status().isOk()));
		List<Boolean> flags = JsonPath.read(page, "$.items[*].imported");

		assertThat(flags).containsOnlyOnce(true);
		assertThat((List<String>) JsonPath.read(page, "$.items[?(@.imported == true)].id")).containsExactly(imported.toString());
		assertThat((List<String>) JsonPath.read(page, "$.items[*].id")).contains(ordinary.toString(), transfer[0].toString(),
				transfer[1].toString(), parcel.toString(), imported.toString());
		for (UUID id : new UUID[] { ordinary, transfer[0], transfer[1], parcel }) {
			mvc.perform(get(transactionsUrl(workspace) + "/" + id).cookie(session(s.owner().token()))).andExpect(status().isOk())
					.andExpect(jsonPath("$.imported").value(false));
		}
		mvc.perform(get(transactionsUrl(workspace) + "/" + imported).cookie(session(s.owner().token()))).andExpect(status().isOk())
				.andExpect(jsonPath("$.imported").value(true));
	}

	@Test
	void theKeyItselfNeverLeavesTheApi() throws Exception {
		PendingScenario s = scenario();
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, type, amount_cents, occurred_on, description, created_by,
				                          import_key)
				values (:w, :a, 'EXPENSE', -2000, date '2026-11-02', 'Importada', :u, 'id:segredo-da-chave')""")
				.param("w", s.owner().workspaceId()).param("a", s.checking()).param("u", s.owner().userId()).update();

		String page = jsonOf(list(mvc, s.owner(), "size=100").andExpect(status().isOk()));

		assertThat(page).doesNotContain("segredo-da-chave").doesNotContainIgnoringCase("importKey").doesNotContainIgnoringCase("import_key");
	}

}
