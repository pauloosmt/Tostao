package com.tostao.tostao_api.invoice;

import static com.tostao.tostao_api.invoice.InvoiceScenario.base;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** História 5: só cartões, todos os papéis leem, e cada workspace só vê o seu. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InvoiceAccessTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	/** As três consultas de um cartão: fatura, lista e resumo. */
	private ResultActions[] threeQueries(UUID workspaceId, UUID accountId, Fixture who) throws Exception {
		String base = base(workspaceId, accountId);
		return new ResultActions[] { mvc.perform(get(base + "/invoices/2024-11").cookie(session(who.token()))),
				mvc.perform(get(base + "/invoices").cookie(session(who.token()))),
				mvc.perform(get(base + "/card-summary").cookie(session(who.token()))) };
	}

	@Test
	void anAccountThatIsNotACardIsRefusedOnTheThreeRoutes() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());

		for (ResultActions result : threeQueries(s.owner().workspaceId(), s.savings(), s.owner())) {
			result.andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Conflito de estado"))
					.andExpect(jsonPath("$.detail").value(containsString("cartão de crédito")));
		}
	}

	@Test
	void aCardWithoutTheDaysIsRefusedForInvoicesNamingWhatIsMissing() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID none = fixtures().insertCard(w, "Sem dias", 0, null, null, null, false);
		UUID onlyClosing = fixtures().insertCard(w, "Só fechamento", 0, null, 20, null, false);
		UUID onlyDue = fixtures().insertCard(w, "Só vencimento", 0, null, null, 28, false);

		ResultActions[] noneResults = threeQueries(w, none, s.owner());
		noneResults[0].andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("closingDay e dueDay")));
		noneResults[1].andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("closingDay e dueDay")));
		noneResults[2].andExpect(status().isOk());
		ResultActions[] closingResults = threeQueries(w, onlyClosing, s.owner());
		closingResults[0].andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("dueDay")));
		ResultActions[] dueResults = threeQueries(w, onlyDue, s.owner());
		dueResults[0].andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value(containsString("closingDay")));
	}

	@Test
	void everyRoleReadsTheThreeRoutes() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID w = s.owner().workspaceId();

		for (Fixture who : new Fixture[] { s.owner(), fixtures().addMember(w, "EDITOR"),
				fixtures().addMember(w, "VIEWER") }) {
			for (ResultActions result : threeQueries(w, s.card(), who)) {
				result.andExpect(status().isOk());
			}
		}
	}

	@Test
	void aNonMemberGetsTheSameNotFoundAsAWorkspaceThatDoesNotExist() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		Fixture outsider = fixtures().workspaceWithOwner();

		ResultActions[] real = threeQueries(w, s.card(), outsider);
		ResultActions[] missing = threeQueries(UUID.randomUUID(), s.card(), outsider);
		for (int i = 0; i < 3; i++) {
			real[i].andExpect(status().isNotFound());
			missing[i].andExpect(status().isNotFound());
			String a = real[i].andReturn().getResponse().getContentAsString();
			String b = missing[i].andReturn().getResponse().getContentAsString();
			assertThat((String) JsonPath.read(a, "$.title")).isEqualTo(JsonPath.read(b, "$.title"));
			assertThat((String) JsonPath.read(a, "$.detail")).isEqualTo(JsonPath.read(b, "$.detail"));
		}
	}

	@Test
	void anAccountOfAnotherWorkspaceAnUnknownOneAndAMalformedOneAreNotFound() throws Exception {
		InvoiceScenario a = InvoiceScenario.create(fixtures());
		InvoiceScenario b = InvoiceScenario.create(fixtures());
		UUID w = a.owner().workspaceId();

		for (ResultActions result : threeQueries(w, b.card(), a.owner())) {
			result.andExpect(status().isNotFound());
		}
		for (ResultActions result : threeQueries(w, UUID.randomUUID(), a.owner())) {
			result.andExpect(status().isNotFound());
		}
		for (String path : new String[] { "/invoices/2024-11", "/invoices", "/card-summary" }) {
			mvc.perform(get("/api/v1/workspaces/" + w + "/accounts/abc" + path).cookie(session(a.owner().token())))
					.andExpect(status().isNotFound());
		}
	}

	@Test
	void withoutASessionTheQueriesAreRefused() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		String base = base(s.owner().workspaceId(), s.card());

		for (String path : new String[] { "/invoices/2024-11", "/invoices", "/card-summary" }) {
			mvc.perform(get(base + path)).andExpect(status().isUnauthorized());
		}
	}

	@Test
	void anInvoiceNeverSumsTransactionsOfAnotherWorkspace() throws Exception {
		InvoiceScenario a = InvoiceScenario.create(fixtures());
		InvoiceScenario b = InvoiceScenario.create(fixtures());
		// no workspace B, o mesmo cartão ganha uma compra enorme na mesma fatura
		fixtures().insertLedgerTransaction(b.owner().workspaceId(), b.card(), b.owner().userId(), b.food(), "EXPENSE",
				999_999, java.time.LocalDate.of(2024, 11, 10), "Só do B", false);

		InvoiceScenario.invoice(mvc, a.owner(), a.card(), "2024-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCents").value(62_000));
		InvoiceScenario.invoice(mvc, b.owner(), b.card(), "2024-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCents").value(62_000 + 999_999));
	}

}
