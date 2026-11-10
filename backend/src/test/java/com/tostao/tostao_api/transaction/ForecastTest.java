package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.transaction.PendingScenario.account;
import static com.tostao.tostao_api.transaction.PendingScenario.accounts;
import static com.tostao.tostao_api.transaction.PendingScenario.balanceReport;
import static com.tostao.tostao_api.transaction.PendingScenario.dashboard;
import static com.tostao.tostao_api.transaction.PendingScenario.list;
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

/** História 4: ver o previsto (saldo previsto, filtro de estado e bloco pendente do dashboard). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ForecastTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	/** Corrente: 100000, efetivada 5000; pendentes: despesa 80000, receita 30000 e transferência 10000 para a Poupança. */
	private PendingScenario withPendings() {
		WorkspaceFixtures f = fixtures();
		PendingScenario s = PendingScenario.create(f);
		UUID w = s.owner().workspaceId();
		UUID u = s.owner().userId();
		f.insertLedgerTransaction(w, s.checking(), u, s.food(), "EXPENSE", 80_000, LocalDate.of(2026, 11, 20), "Aluguel", false, "PENDING");
		f.insertLedgerTransaction(w, s.checking(), u, s.salary(), "INCOME", 30_000, LocalDate.of(2026, 11, 25), "Adiantamento", false, "PENDING");
		f.insertTransferPair(w, s.checking(), s.savings(), u, 10_000, LocalDate.of(2026, 11, 28), "Reserva", "PENDING");
		return s;
	}

	@Test
	void eachAccountShowsTheBalancePendingAndProjected() throws Exception {
		PendingScenario s = withPendings();

		account(mvc, s.owner(), s.checking()).andExpect(status().isOk()).andExpect(jsonPath("$.balanceCents").value(95_000))
				.andExpect(jsonPath("$.pendingCents").value(-60_000)).andExpect(jsonPath("$.projectedBalanceCents").value(35_000));
		account(mvc, s.owner(), s.savings()).andExpect(jsonPath("$.balanceCents").value(0))
				.andExpect(jsonPath("$.pendingCents").value(10_000)).andExpect(jsonPath("$.projectedBalanceCents").value(10_000));
		accounts(mvc, s.owner()).andExpect(jsonPath("$[?(@.name=='Corrente')].projectedBalanceCents").value(35_000));
	}

	@Test
	void anAccountWithoutPendingsHasPendingZeroAndProjectedEqualToTheBalance() throws Exception {
		PendingScenario s = withPendings();

		account(mvc, s.owner(), s.card()).andExpect(jsonPath("$.pendingCents").value(0))
				.andExpect(jsonPath("$.balanceCents").value(0)).andExpect(jsonPath("$.projectedBalanceCents").value(0));
	}

	@Test
	void theBalanceReportHasTheProjectedPerAccountAndInTotal() throws Exception {
		PendingScenario s = withPendings();

		balanceReport(mvc, s.owner()).andExpect(status().isOk()).andExpect(jsonPath("$.totalBalanceCents").value(95_000))
				.andExpect(jsonPath("$.totalProjectedCents").value(45_000))
				.andExpect(jsonPath("$.accounts[?(@.name=='Corrente')].projectedBalanceCents").value(35_000))
				.andExpect(jsonPath("$.accounts[?(@.name=='Poupança')].pendingCents").value(10_000));
	}

	@Test
	void anArchivedAccountLeavesTheProjectedTotalLikeItLeavesTheBalanceTotal() throws Exception {
		PendingScenario s = withPendings();
		owner.client().sql("update accounts set archived = true where id = :id").param("id", s.savings()).update();

		balanceReport(mvc, s.owner()).andExpect(jsonPath("$.totalBalanceCents").value(95_000))
				.andExpect(jsonPath("$.totalProjectedCents").value(35_000));
	}

	@Test
	void theListFiltersByStatusCombinedWithTheOtherFilters() throws Exception {
		PendingScenario s = withPendings();

		list(mvc, s.owner(), "status=PENDING").andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(4));
		list(mvc, s.owner(), "status=CLEARED").andExpect(jsonPath("$.totalItems").value(1))
				.andExpect(jsonPath("$.items[0].description").value("Efetivada"));
		list(mvc, s.owner(), "status=PENDING&type=EXPENSE").andExpect(jsonPath("$.totalItems").value(1));
		list(mvc, s.owner(), "status=PENDING&accountId=" + s.savings()).andExpect(jsonPath("$.totalItems").value(1));
		list(mvc, s.owner(), "status=PENDING&from=2026-11-21&to=2026-11-30").andExpect(jsonPath("$.totalItems").value(3));
		// sem filtro, tudo
		list(mvc, s.owner(), "").andExpect(jsonPath("$.totalItems").value(5));
	}

	@Test
	void anInvalidStatusFilterIsRejectedNamingTheField() throws Exception {
		PendingScenario s = withPendings();

		for (String bad : new String[] { "OUTRO", "pending", "1" }) {
			list(mvc, s.owner(), "status=" + bad).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("status"));
		}
	}

	@Test
	void theDashboardPendingBlockIsOfThePeriodWithoutTransfers() throws Exception {
		PendingScenario s = withPendings();

		dashboard(mvc, s.owner(), "month=2026-11").andExpect(status().isOk())
				.andExpect(jsonPath("$.pending.incomeCents").value(30_000))
				.andExpect(jsonPath("$.pending.expenseCents").value(80_000)).andExpect(jsonPath("$.pending.count").value(2));
		// uma semana só com a receita pendente de 25/11 (as transferências não entram)
		dashboard(mvc, s.owner(), "week=2026-W48").andExpect(jsonPath("$.pending.incomeCents").value(30_000))
				.andExpect(jsonPath("$.pending.expenseCents").value(0)).andExpect(jsonPath("$.pending.count").value(1));
		// período sem pendentes
		dashboard(mvc, s.owner(), "month=2026-10").andExpect(jsonPath("$.pending.incomeCents").value(0))
				.andExpect(jsonPath("$.pending.expenseCents").value(0)).andExpect(jsonPath("$.pending.count").value(0));
	}

}
