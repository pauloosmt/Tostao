package com.tostao.tostao_api.dashboard;

import static com.tostao.tostao_api.dashboard.DashboardScenario.base;
import static com.tostao.tostao_api.dashboard.DashboardScenario.dashboard;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** US6: quem não é membro não descobre nada; os três papéis leem; nada escreve; sem sessão não entra. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DashboardAccessTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	/** Corpo sem o campo {@code instance}, que repete a rota pedida e por isso muda de um caso para outro. */
	private static String withoutInstance(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", "");
	}

	@Test
	void aNonMemberGetsTheSameNotFoundAsAMissingWorkspaceForMonthAndWeek() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());
		Fixture stranger = fixtures().workspaceWithOwner();

		for (String query : new String[] { "month=2026-11", "week=2026-W45", "" }) {
			MvcResult foreign = dashboard(mvc, s.owner().workspaceId(), stranger, query)
					.andExpect(status().isNotFound()).andReturn();
			MvcResult missing = dashboard(mvc, UUID.randomUUID(), stranger, query).andExpect(status().isNotFound())
					.andReturn();

			assertThat(withoutInstance(foreign)).isEqualTo(withoutInstance(missing));
			assertThat(foreign.getResponse().getContentAsString()).doesNotContain("Alimentação")
					.doesNotContain("totals");
		}
	}

	@Test
	void ownerEditorAndViewerAllReadTheDashboard() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());
		Fixture editor = fixtures().addMember(s.owner().workspaceId(), "EDITOR");
		Fixture viewer = fixtures().addMember(s.owner().workspaceId(), "VIEWER");

		for (Fixture who : new Fixture[] { s.owner(), editor, viewer }) {
			for (String query : new String[] { "month=2026-11", "week=2026-W45" }) {
				MvcResult result = dashboard(mvc, who, query).andExpect(status().isOk()).andReturn();
				assertThat(((Number) JsonPath.read(result.getResponse().getContentAsString(),
						"$.totals.incomeCents")).longValue()).isEqualTo(250_000);
			}
		}
	}

	@Test
	void withoutASessionTheRequestIsRefused() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());

		mvc.perform(get(base(s.owner().workspaceId()) + "?month=2026-11")).andExpect(status().isUnauthorized());
	}

	@Test
	void everyMethodOtherThanGetIsRefusedAndChangesNothing() throws Exception {
		DashboardScenario s = DashboardScenario.create(fixtures());
		String before = DashboardScenario.fingerprint(owner, s.owner().workspaceId());
		String url = base(s.owner().workspaceId());

		for (MockHttpServletRequestBuilder request : new MockHttpServletRequestBuilder[] { post(url), put(url),
				patch(url), delete(url) }) {
			mvc.perform(request.cookie(session(s.owner().token())).contentType(MediaType.APPLICATION_JSON)
					.content("{\"totals\":{\"incomeCents\":1}}")).andExpect(status().isMethodNotAllowed());
		}

		assertThat(DashboardScenario.fingerprint(owner, s.owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void eachWorkspaceDashboardNeverIncludesTheOthersAccountsCategoriesOrTransactions() throws Exception {
		DashboardScenario first = DashboardScenario.create(fixtures());
		DashboardScenario second = DashboardScenario.create(fixtures());
		// no segundo workspace, uma despesa grande que não pode aparecer no primeiro
		fixtures().insertLedgerTransaction(second.owner().workspaceId(), second.checking(), second.owner().userId(),
				second.food(), "EXPENSE", 900_000, java.time.LocalDate.of(2026, 11, 3), "Só do segundo", false);

		String firstJson = dashboard(mvc, first.owner(), "month=2026-11").andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();
		String secondJson = dashboard(mvc, second.owner(), "month=2026-11").andExpect(status().isOk()).andReturn()
				.getResponse().getContentAsString();

		assertThat(((Number) JsonPath.read(firstJson, "$.totals.expenseCents")).longValue()).isEqualTo(15_000);
		assertThat(((Number) JsonPath.read(secondJson, "$.totals.expenseCents")).longValue()).isEqualTo(915_000);
		assertThat(firstJson).doesNotContain(second.food().toString()).doesNotContain(second.checking().toString());
		assertThat(secondJson).doesNotContain(first.food().toString()).doesNotContain(first.checking().toString());
	}

}
