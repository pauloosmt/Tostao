package com.tostao.tostao_api.invoice;

import static com.tostao.tostao_api.invoice.InvoiceScenario.invoice;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** Histórias 1 e 3: a fatura de um mês de vencimento, com lançamentos, pagamentos e situação. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InvoiceTest {

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
	void novemberInvoiceHasTheRightDatesLinesTotalAndPayments() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());

		invoice(mvc, s.owner(), s.card(), "2024-11").andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(jsonPath("$.month").value("2024-11"))
				.andExpect(jsonPath("$.periodStart").value("2024-10-21"))
				.andExpect(jsonPath("$.closingDate").value("2024-11-20"))
				.andExpect(jsonPath("$.dueDate").value("2024-11-28"))
				.andExpect(jsonPath("$.totalCents").value(62_000))
				.andExpect(jsonPath("$.paidCents").value(25_000))
				.andExpect(jsonPath("$.remainingCents").value(37_000))
				.andExpect(jsonPath("$.status").value("OVERDUE"))
				// compras, estorno e saque, em ordem de data; sem a compra de 20/10, a de 21/11, a excluída e o pagamento
				.andExpect(jsonPath("$.lines.length()").value(5))
				.andExpect(jsonPath("$.lines[*].date").value(org.hamcrest.Matchers.contains("2024-10-21", "2024-11-05",
						"2024-11-12", "2024-11-15", "2024-11-20")))
				.andExpect(jsonPath("$.lines[*].chargeCents").value(org.hamcrest.Matchers.contains(30_000, 20_000,
						-4_000, 6_000, 10_000)))
				.andExpect(jsonPath("$.lines[*].type").value(org.hamcrest.Matchers.contains("EXPENSE", "EXPENSE",
						"INCOME", "TRANSFER", "EXPENSE")))
				.andExpect(jsonPath("$.lines[0].categoryName").value("Mercado"))
				.andExpect(jsonPath("$.lines[0].categoryId").value(s.food().toString()))
				.andExpect(jsonPath("$.lines[2].categoryName").doesNotExist());
	}

	@Test
	void neighbouringInvoicesTakeTheirOwnPurchasesAndTheClosingDayBelongsToTheClosingInvoice() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());

		// a compra de 20/10 (dia do fechamento) é de outubro; a de 21/10 já é de novembro
		invoice(mvc, s.owner(), s.card(), "2024-10").andExpect(status().isOk())
				.andExpect(jsonPath("$.periodStart").value("2024-09-21"))
				.andExpect(jsonPath("$.closingDate").value("2024-10-20"))
				.andExpect(jsonPath("$.dueDate").value("2024-10-28"))
				.andExpect(jsonPath("$.totalCents").value(12_345)).andExpect(jsonPath("$.paidCents").value(0))
				.andExpect(jsonPath("$.status").value("OVERDUE")).andExpect(jsonPath("$.lines.length()").value(1));
		// a compra de 21/11 é de dezembro
		invoice(mvc, s.owner(), s.card(), "2024-12").andExpect(status().isOk())
				.andExpect(jsonPath("$.periodStart").value("2024-11-21"))
				.andExpect(jsonPath("$.closingDate").value("2024-12-20"))
				.andExpect(jsonPath("$.dueDate").value("2024-12-28"))
				.andExpect(jsonPath("$.totalCents").value(5_000)).andExpect(jsonPath("$.lines.length()").value(1));
	}

	@Test
	void aPaymentThatCoversTheTotalMakesItPaidAndAnOverpaymentNeverGoesNegative() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID w = s.owner().workspaceId();

		fixtures().insertTransferPair(w, s.checking(), s.card(), s.owner().userId(), 37_000, LocalDate.of(2024, 12, 1),
				"Resto da fatura");
		invoice(mvc, s.owner(), s.card(), "2024-11").andExpect(jsonPath("$.paidCents").value(62_000))
				.andExpect(jsonPath("$.remainingCents").value(0)).andExpect(jsonPath("$.status").value("PAID"));

		fixtures().insertTransferPair(w, s.checking(), s.card(), s.owner().userId(), 50_000, LocalDate.of(2024, 12, 2),
				"Pagamento a mais");
		invoice(mvc, s.owner(), s.card(), "2024-11").andExpect(jsonPath("$.paidCents").value(112_000))
				.andExpect(jsonPath("$.remainingCents").value(0)).andExpect(jsonPath("$.status").value("PAID"));
	}

	@Test
	void aPaymentOfThePreviousInvoiceMadeInsideThisPeriodDoesNotCountForThisOne() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());

		// o pagamento de 25/11 (depois do fechamento de novembro) paga novembro; a fatura de dezembro (21/11 a 20/12)
		// o tem dentro do seu período, mas ele não é pagamento dela
		invoice(mvc, s.owner(), s.card(), "2024-12").andExpect(jsonPath("$.paidCents").value(0))
				.andExpect(jsonPath("$.remainingCents").value(5_000));
		// e ele também não entra na lista de lançamentos de dezembro
		invoice(mvc, s.owner(), s.card(), "2024-12").andExpect(jsonPath("$.lines.length()").value(1));
	}

	@Test
	void aMonthWithoutPurchasesIsEmptyAndPaidOnceClosed() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());

		invoice(mvc, s.owner(), s.card(), "2024-01").andExpect(status().isOk())
				.andExpect(jsonPath("$.lines.length()").value(0)).andExpect(jsonPath("$.totalCents").value(0))
				.andExpect(jsonPath("$.remainingCents").value(0)).andExpect(jsonPath("$.status").value("PAID"));
	}

	@Test
	void onlyRefundsGiveANegativeTotalWithNothingLeftToPay() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID other = fixtures().insertCard(w, "Master", 0, null, 20, 28, false);
		fixtures().insertLedgerTransaction(w, other, s.owner().userId(), null, "INCOME", 3_000,
				LocalDate.of(2024, 11, 10), "Estorno", false);

		invoice(mvc, s.owner(), other, "2024-11").andExpect(jsonPath("$.totalCents").value(-3_000))
				.andExpect(jsonPath("$.remainingCents").value(0)).andExpect(jsonPath("$.status").value("PAID"))
				.andExpect(jsonPath("$.lines[0].chargeCents").value(-3_000));
	}

	@Test
	void theInitialBalanceNeverEntersAnyInvoiceAndAnArchivedCardStillAnswers() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID debt = fixtures().insertCard(w, "Com dívida", -100_000, null, 20, 28, true);

		invoice(mvc, s.owner(), debt, "2024-11").andExpect(status().isOk()).andExpect(jsonPath("$.totalCents").value(0));
		owner.client().sql("update accounts set archived = true where id = :id").param("id", s.card()).update();
		invoice(mvc, s.owner(), s.card(), "2024-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCents").value(62_000));
	}

	@Test
	void theSumIsExactToTheCent() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID exact = fixtures().insertCard(w, "Centavos", 0, null, 20, 28, false);
		fixtures().insertLedgerTransaction(w, exact, s.owner().userId(), s.food(), "EXPENSE", 10,
				LocalDate.of(2024, 11, 1), "Dez centavos", false);
		fixtures().insertLedgerTransaction(w, exact, s.owner().userId(), s.food(), "EXPENSE", 20,
				LocalDate.of(2024, 11, 2), "Vinte centavos", false);

		invoice(mvc, s.owner(), exact, "2024-11").andExpect(jsonPath("$.totalCents").value(30));
	}

	@Test
	void otherCardCalendarsGiveTheRightDates() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID dueFirst = fixtures().insertCard(w, "Fecha 25 vence 5", 0, null, 25, 5, false);
		UUID last = fixtures().insertCard(w, "Fecha 31 vence 31", 0, null, 31, 31, false);
		UUID lastClosing = fixtures().insertCard(w, "Fecha 31 vence 5", 0, null, 31, 5, false);

		invoice(mvc, s.owner(), dueFirst, "2024-12").andExpect(jsonPath("$.periodStart").value("2024-10-26"))
				.andExpect(jsonPath("$.closingDate").value("2024-11-25"))
				.andExpect(jsonPath("$.dueDate").value("2024-12-05"));
		invoice(mvc, s.owner(), last, "2027-02").andExpect(jsonPath("$.periodStart").value("2027-01-01"))
				.andExpect(jsonPath("$.closingDate").value("2027-01-31"))
				.andExpect(jsonPath("$.dueDate").value("2027-02-28"));
		invoice(mvc, s.owner(), last, "2028-02").andExpect(jsonPath("$.dueDate").value("2028-02-29"));
		invoice(mvc, s.owner(), lastClosing, "2027-03").andExpect(jsonPath("$.periodStart").value("2027-02-01"))
				.andExpect(jsonPath("$.closingDate").value("2027-02-28"))
				.andExpect(jsonPath("$.dueDate").value("2027-03-05"));
		invoice(mvc, s.owner(), lastClosing, "2028-03").andExpect(jsonPath("$.closingDate").value("2028-02-29"));
	}

	@Test
	void aBadMonthIsRejectedNamingTheField() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());

		for (String bad : new String[] { "2024-1", "202411", "2024-13", "2024-00", "1999-12", "2101-01", "2024-11-01",
				"novembro" }) {
			invoice(mvc, s.owner(), s.card(), bad).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("month"));
		}
	}

	@Test
	void theQueryNeverChangesAnyData() throws Exception {
		InvoiceScenario s = InvoiceScenario.create(fixtures());
		Fixture me = s.owner();
		String before = InvoiceScenario.financeFingerprint(owner, me.workspaceId());

		invoice(mvc, me, s.card(), "2024-11").andExpect(status().isOk());
		invoice(mvc, me, s.card(), "2024-10").andExpect(status().isOk());
		InvoiceScenario.list(mvc, me, s.card(), "").andExpect(status().isOk());
		InvoiceScenario.summary(mvc, me, s.card()).andExpect(status().isOk());

		assertThat(InvoiceScenario.financeFingerprint(owner, me.workspaceId())).isEqualTo(before);
	}

}
