package com.tostao.tostao_api.invoice;

import static com.tostao.tostao_api.invoice.InvoiceScenario.invoice;
import static com.tostao.tostao_api.invoice.InvoiceScenario.list;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
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

/** História 4: as faturas de um intervalo de meses de vencimento. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InvoiceListTest {

	private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	@Test
	void eachListedInvoiceIsEqualToTheIndividualQueryOfThatMonth() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());

		String json = body(list(mvc, s.owner(), s.card(), "from=2024-09&to=2024-12").andExpect(status().isOk())
				.andExpect(jsonPath("$.invoices.length()").value(4))
				.andExpect(jsonPath("$.invoices[*].month").value(
						org.hamcrest.Matchers.contains("2024-12", "2024-11", "2024-10", "2024-09")))
				.andExpect(jsonPath("$.invoices[0].lines").doesNotExist()));

		for (int i = 0; i < 4; i++) {
			String month = JsonPath.read(json, "$.invoices[" + i + "].month");
			String individual = body(invoice(mvc, s.owner(), s.card(), month).andExpect(status().isOk()));
			for (String field : new String[] { "periodStart", "closingDate", "dueDate", "totalCents", "paidCents",
					"remainingCents", "status" }) {
				Object fromList = JsonPath.read(json, "$.invoices[" + i + "]." + field);
				Object fromOne = JsonPath.read(individual, "$." + field);
				assertThat(fromList).as(month + " " + field).isEqualTo(fromOne);
			}
		}
		// e os números do quickstart
		assertThat((Integer) JsonPath.read(json, "$.invoices[1].totalCents")).isEqualTo(62_000);
		assertThat((Integer) JsonPath.read(json, "$.invoices[1].paidCents")).isEqualTo(25_000);
		assertThat((Integer) JsonPath.read(json, "$.invoices[2].totalCents")).isEqualTo(12_345);
		assertThat((Integer) JsonPath.read(json, "$.invoices[0].totalCents")).isEqualTo(5_000);
	}

	@Test
	void withoutParametersItReturnsTheSixInvoicesUpToTheOpenOne() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());

		LocalDate before = LocalDate.now(BRASILIA);
		String json = body(list(mvc, s.owner(), s.card(), "").andExpect(status().isOk())
				.andExpect(jsonPath("$.invoices.length()").value(6)));
		LocalDate after = LocalDate.now(BRASILIA);

		// fecha dia 20: a aberta vence neste mês até o dia 20 e no seguinte depois; as outras vão de um em um mês para trás
		String first = JsonPath.read(json, "$.invoices[0].month");
		assertThat(first).isIn(expectedOpenMonth(before), expectedOpenMonth(after));
		YearMonth month = YearMonth.parse(first);
		for (int i = 0; i < 6; i++) {
			assertThat((String) JsonPath.read(json, "$.invoices[" + i + "].month"))
					.isEqualTo(month.minusMonths(i).toString());
		}
		assertThat((String) JsonPath.read(json, "$.invoices[0].status")).isEqualTo("OPEN");
	}

	private static String expectedOpenMonth(LocalDate today) {
		YearMonth month = YearMonth.from(today);
		return (today.getDayOfMonth() <= 20 ? month : month.plusMonths(1)).toString();
	}

	@Test
	void aCardWithoutPurchasesHasAllInvoicesAtZero() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID empty = fixtures().insertCard(s.owner().workspaceId(), "Vazio", 0, null, 20, 28, false);

		list(mvc, s.owner(), empty, "from=2024-01&to=2024-03").andExpect(status().isOk())
				.andExpect(jsonPath("$.invoices.length()").value(3))
				.andExpect(jsonPath("$.invoices[*].totalCents").value(org.hamcrest.Matchers.contains(0, 0, 0)))
				.andExpect(jsonPath("$.invoices[*].status").value(org.hamcrest.Matchers.contains("PAID", "PAID", "PAID")));
	}

	@Test
	void aSingleMonthAndTheThirtySixMonthLimitAreAccepted() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());

		list(mvc, s.owner(), s.card(), "from=2024-11&to=2024-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.invoices.length()").value(1));
		list(mvc, s.owner(), s.card(), "from=2022-01&to=2024-12").andExpect(status().isOk())
				.andExpect(jsonPath("$.invoices.length()").value(36));
	}

	@Test
	void withOnlyOneEndTheWindowHasSixMonths() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());

		list(mvc, s.owner(), s.card(), "to=2024-12").andExpect(status().isOk())
				.andExpect(jsonPath("$.invoices.length()").value(6))
				.andExpect(jsonPath("$.invoices[0].month").value("2024-12"))
				.andExpect(jsonPath("$.invoices[5].month").value("2024-07"));
		list(mvc, s.owner(), s.card(), "from=2024-06").andExpect(status().isOk())
				.andExpect(jsonPath("$.invoices.length()").value(6))
				.andExpect(jsonPath("$.invoices[0].month").value("2024-11"))
				.andExpect(jsonPath("$.invoices[5].month").value("2024-06"));
	}

	@Test
	void badIntervalsAreRejectedNamingTheField() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());

		list(mvc, s.owner(), s.card(), "from=2024-1&to=2024-12").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("from"));
		list(mvc, s.owner(), s.card(), "from=2024-01&to=2101-01").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("to"));
		list(mvc, s.owner(), s.card(), "from=1999-12&to=2024-12").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("from"));
		// início depois do fim
		list(mvc, s.owner(), s.card(), "from=2024-12&to=2024-01").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("from"));
		// 37 meses
		list(mvc, s.owner(), s.card(), "from=2021-12&to=2024-12").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("to"));
		// texto vazio é inválido, não "ausente"
		list(mvc, s.owner(), s.card(), "from=&to=2024-12").andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("from"));
	}

}
