package com.tostao.tostao_api.dashboard;

import static com.tostao.tostao_api.dashboard.DashboardScenario.base;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** US7: mês ou semana inválidos viram 400 com o campo; períodos válidos e vazios não são erro. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class DashboardValidationTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private Fixture user() {
		Fixture fx = new WorkspaceFixtures(owner, tokens).workspaceWithOwner();
		new WorkspaceFixtures(owner, tokens).insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 0, false);
		return fx;
	}

	/** Pede o painel com os parâmetros como estão (sem recodificar), um valor por nome. */
	private ResultActions ask(Fixture who, String... nameValuePairs) throws Exception {
		MockHttpServletRequestBuilder request = get(base(who.workspaceId())).cookie(session(who.token()));
		for (int i = 0; i < nameValuePairs.length; i += 2) {
			request = request.param(nameValuePairs[i], nameValuePairs[i + 1]);
		}
		return mvc.perform(request);
	}

	@ParameterizedTest
	@ValueSource(strings = { "2026-13", "2026-00", "2026-1", "novembro", "2026-11-05", "1999-12", "2101-01", "",
			" ", "2026-11 ", "2026/11" })
	void invalidMonthsAreRefusedOnTheMonthField(String month) throws Exception {
		ask(user(), "month", month).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errors[0].field").value("month"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "2025-W53", "2026-W54", "2026-W00", "2026W45", "1999-W52", "2101-W01", "", "2026-w45",
			"2026-W5", "2026-45", "semana", "2100-W53" })
	void invalidWeeksAreRefusedOnTheWeekField(String week) throws Exception {
		ask(user(), "week", week).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errors[0].field").value("week"));
	}

	@Test
	void monthAndWeekTogetherAreRefusedNamingBoth() throws Exception {
		ask(user(), "month", "2026-11", "week", "2026-W45").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("week"))
				.andExpect(jsonPath("$.errors[0].message").value(org.hamcrest.Matchers.allOf(
						org.hamcrest.Matchers.containsString("month"), org.hamcrest.Matchers.containsString("week"))));
	}

	@Test
	void aRepeatedMonthParameterIsRefusedNotAnInternalError() throws Exception {
		ask(user(), "month", "2026-11", "month", "2026-10").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("month"));
	}

	@Test
	void validBoundaryPeriodsAreAccepted() throws Exception {
		Fixture who = user();

		ask(who, "week", "2026-W53").andExpect(status().isOk());
		ask(who, "week", "2000-W01").andExpect(status().isOk());
		ask(who, "week", "2100-W52").andExpect(status().isOk());
		ask(who, "month", "2000-01").andExpect(status().isOk());
		ask(who, "month", "2100-12").andExpect(status().isOk());
	}

	@Test
	void aValidMonthWithoutTransactionsIsAZeroedDashboard() throws Exception {
		Fixture who = user();

		String json = ask(who, "month", "2027-03").andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();

		assertThat(((Number) JsonPath.read(json, "$.totals.resultCents")).longValue()).isZero();
		assertThat(JsonPath.<java.util.List<?>>read(json, "$.daily")).hasSize(31);
	}

	@Test
	void errorResponsesLeakNoInternals() throws Exception {
		String json = ask(user(), "month", "2026-13").andExpect(status().isBadRequest()).andReturn().getResponse()
				.getContentAsString();

		assertThat(json).doesNotContain("Exception").doesNotContain("at com.tostao").doesNotContain("stackTrace")
				.doesNotContain("org.springframework");
	}

	@Test
	void unknownQueryParametersAreIgnored() throws Exception {
		ask(user(), "month", "2026-11", "foo", "bar").andExpect(status().isOk())
				.andExpect(jsonPath("$.period.id").value("2026-11"));
	}

}
