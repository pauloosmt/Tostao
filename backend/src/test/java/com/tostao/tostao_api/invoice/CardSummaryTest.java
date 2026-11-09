package com.tostao.tostao_api.invoice;

import static com.tostao.tostao_api.invoice.InvoiceScenario.invoice;
import static com.tostao.tostao_api.invoice.InvoiceScenario.summary;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** História 2: o resumo do cartão (limite, devido, disponível e fatura aberta). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CardSummaryTest {

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

	@Test
	void showsLimitOwedAndAvailableAndTheOwedIsTheNegativeBalanceOfTheAccount() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());

		// saldo = -(12345 + 30000 + 20000 + 10000 + 5000) + 4000 (estorno) - 6000 (saque) + 25000 (pagamento)
		summary(mvc, s.owner(), s.card()).andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value(s.card().toString()))
				.andExpect(jsonPath("$.name").value("Visa")).andExpect(jsonPath("$.archived").value(false))
				.andExpect(jsonPath("$.limitCents").value(500_000)).andExpect(jsonPath("$.owedCents").value(54_345))
				.andExpect(jsonPath("$.availableCents").value(445_655));

		// FR-016: o mesmo valor que a consulta de contas da spec 002 devolve
		String account = mvc.perform(get(InvoiceScenario.base(s.owner().workspaceId(), s.card()))
				.cookie(session(s.owner().token()))).andReturn().getResponse().getContentAsString();
		assertThat(((Number) JsonPath.read(account, "$.balanceCents")).longValue()).isEqualTo(-54_345L);
	}

	@Test
	void aCardWithoutALimitHasNoLimitAndNoAvailableButStillShowsWhatIsOwed() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID noLimit = fixtures().insertCard(w, "Sem limite", 0, null, 20, 28, false);
		fixtures().insertLedgerTransaction(w, noLimit, s.owner().userId(), s.food(), "EXPENSE", 7_000,
				LocalDate.of(2024, 11, 3), "Compra", false);

		summary(mvc, s.owner(), noLimit).andExpect(status().isOk()).andExpect(jsonPath("$.limitCents").doesNotExist())
				.andExpect(jsonPath("$.availableCents").doesNotExist()).andExpect(jsonPath("$.owedCents").value(7_000));
	}

	@Test
	void aCreditBalanceOwesNothingAndRaisesTheAvailable() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID card = fixtures().insertCard(w, "Com crédito", 0, 500_000L, 20, 28, false);
		fixtures().insertTransferPair(w, s.checking(), card, s.owner().userId(), 10_000, LocalDate.of(2024, 11, 3),
				"Pagamento a mais");

		summary(mvc, s.owner(), card).andExpect(jsonPath("$.owedCents").value(0))
				.andExpect(jsonPath("$.availableCents").value(510_000));
	}

	@Test
	void whatIsOwedAboveTheLimitMakesTheAvailableNegativeWithoutError() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID card = fixtures().insertCard(w, "Estourado", 0, 10_000L, 20, 28, false);
		fixtures().insertLedgerTransaction(w, card, s.owner().userId(), s.food(), "EXPENSE", 25_000,
				LocalDate.of(2024, 11, 3), "Compra grande", false);

		summary(mvc, s.owner(), card).andExpect(status().isOk()).andExpect(jsonPath("$.owedCents").value(25_000))
				.andExpect(jsonPath("$.availableCents").value(-15_000));
	}

	@Test
	void theOpenInvoiceIsTheOneThatContainsToday() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		// uma compra de hoje entra no total da fatura aberta
		LocalDate before = LocalDate.now(BRASILIA);
		fixtures().insertLedgerTransaction(w, s.card(), s.owner().userId(), s.food(), "EXPENSE", 1_234, before,
				"Compra de hoje", false);

		String json = summary(mvc, s.owner(), s.card()).andExpect(status().isOk())
				.andExpect(jsonPath("$.openInvoice.totalCents").value(1_234)).andReturn().getResponse()
				.getContentAsString();
		String month = JsonPath.read(json, "$.openInvoice.month");
		String closing = JsonPath.read(json, "$.openInvoice.closingDate");
		String periodStart = JsonPath.read(json, "$.openInvoice.periodStart");

		// fecha dia 20 e vence dia 28: até o dia 20 a aberta vence neste mês, depois, no seguinte
		LocalDate after = LocalDate.now(BRASILIA);
		assertThat(month).isIn(expectedOpenMonth(before), expectedOpenMonth(after));
		assertThat(LocalDate.parse(closing).getDayOfMonth()).isEqualTo(20);
		assertThat(LocalDate.parse(periodStart)).isBeforeOrEqualTo(before);
		assertThat(LocalDate.parse(closing)).isAfterOrEqualTo(before);
		// e é a mesma que a consulta individual daquele mês mostra
		invoice(mvc, s.owner(), s.card(), month).andExpect(jsonPath("$.totalCents").value(1_234))
				.andExpect(jsonPath("$.status").value("OPEN")).andExpect(jsonPath("$.paidCents").value(0));
	}

	private static String expectedOpenMonth(LocalDate today) {
		YearMonth month = YearMonth.from(today);
		return (today.getDayOfMonth() <= 20 ? month : month.plusMonths(1)).toString();
	}

	@Test
	void aCardWithoutDaysStillGivesTheSummaryWithoutAnOpenInvoice() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID noDays = fixtures().insertCard(w, "Sem dias", 0, 1_000L, null, null, false);

		summary(mvc, s.owner(), noDays).andExpect(status().isOk()).andExpect(jsonPath("$.limitCents").value(1_000))
				.andExpect(jsonPath("$.openInvoice").doesNotExist());
	}

	@Test
	void anAccountThatIsNotACardIsRefused() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());

		summary(mvc, s.owner(), s.savings()).andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value(containsString("cartão de crédito")));
	}

	@Test
	void anArchivedCardStillHasASummary() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		Fixture me = s.owner();
		owner.client().sql("update accounts set archived = true where id = :id").param("id", s.card()).update();

		summary(mvc, me, s.card()).andExpect(status().isOk()).andExpect(jsonPath("$.archived").value(true))
				.andExpect(jsonPath("$.owedCents").value(54_345));
	}

}
