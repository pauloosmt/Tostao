package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.transaction.InstallmentScenario.cancel;
import static com.tostao.tostao_api.transaction.InstallmentScenario.plan;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchase;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchaseBody;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsFingerprint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import com.tostao.tostao_api.automation.AutomationService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/** História 4 da 011: as parcelas de uma compra parcelada se efetivam sozinhas, cada uma no seu dia (FR-017). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InstallmentAutoClearTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;
	@Autowired
	AutomationService automation;

	/** O banco é compartilhado: deixa de lado os lançamentos marcados e as recorrências que sobraram de outros testes. */
	@BeforeEach
	void isolate() {
		owner.client().sql("update recurrences set ended_at = now() where ended_at is null").update();
		owner.client().sql("select auto_clear_due(date '2100-12-31')").query(Integer.class).single();
	}

	private InstallmentScenario scenario() {
		return InstallmentScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	private static String withAutoClear(String json, Object value) {
		return json.substring(0, json.length() - 1) + ",\"autoClear\":" + value + "}";
	}

	/** R$ 3.000,00 em 3 parcelas de 100000, em 2026-11-10, 2026-12-10 e 2027-01-10. */
	private ResultActions buy(InstallmentScenario s, String firstStatus, Object autoClear) throws Exception {
		String json = purchaseBody(s.checking(), s.gadgets(), 300_000, 3, "2026-11-10", "Notebook", firstStatus);
		return purchase(mvc, s.owner(), autoClear == null ? json : withAutoClear(json, autoClear));
	}

	private static List<Boolean> markers(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.installments[*].autoClear");
	}

	private static UUID groupOf(ResultActions result) throws Exception {
		return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.groupId"));
	}

	@Test
	void thePendingInstallmentsGetTheMarkerAndTheClearedFirstOneDoesNot() throws Exception {
		InstallmentScenario s = scenario();

		ResultActions result = buy(s, null, true).andExpect(status().isCreated());

		assertThat(markers(result)).containsExactly(false, true, true);
		assertThat(JsonPath.<List<String>>read(result.andReturn().getResponse().getContentAsString(), "$.installments[*].status"))
				.containsExactly("CLEARED", "PENDING", "PENDING");
	}

	@Test
	void aPendingFirstInstallmentGetsTheMarkerToo() throws Exception {
		InstallmentScenario s = scenario();

		assertThat(markers(buy(s, "PENDING", true).andExpect(status().isCreated()))).containsExactly(true, true, true);
	}

	@Test
	void withoutTheMarkerNoInstallmentIsMarked() throws Exception {
		InstallmentScenario s = scenario();

		assertThat(markers(buy(s, null, null).andExpect(status().isCreated()))).containsExactly(false, false, false);
		assertThat(markers(buy(s, "PENDING", false).andExpect(status().isCreated()))).containsExactly(false, false, false);
	}

	@Test
	void aNonBooleanMarkerIsRefusedAndNothingIsWritten() throws Exception {
		InstallmentScenario s = scenario();
		String before = transactionsFingerprint(owner, s.owner().workspaceId());

		buy(s, null, "\"sim\"").andExpect(status().isBadRequest());

		assertThat(transactionsFingerprint(owner, s.owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void theAutomaticRunClearsEachInstallmentOnItsOwnDateAndThePlanFollows() throws Exception {
		InstallmentScenario s = scenario();
		UUID group = groupOf(buy(s, null, true).andExpect(status().isCreated()));

		// no dia da 2ª parcela: só ela vence
		assertThat(automation.runOnce(LocalDate.of(2026, 12, 10)).cleared()).isEqualTo(1);
		plan(mvc, s.owner(), group).andExpect(status().isOk())
				.andExpect(jsonPath("$.totalCents").value(300_000))
				.andExpect(jsonPath("$.clearedCents").value(200_000))
				.andExpect(jsonPath("$.pendingCents").value(100_000))
				.andExpect(jsonPath("$.installments[1].status").value("CLEARED"))
				.andExpect(jsonPath("$.installments[1].autoClear").value(false))
				.andExpect(jsonPath("$.installments[2].status").value("PENDING"))
				.andExpect(jsonPath("$.installments[2].autoClear").value(true));

		// no dia da 3ª
		assertThat(automation.runOnce(LocalDate.of(2027, 1, 10)).cleared()).isEqualTo(1);
		plan(mvc, s.owner(), group).andExpect(jsonPath("$.clearedCents").value(300_000))
				.andExpect(jsonPath("$.pendingCents").value(0));
	}

	@Test
	void withoutTheMarkerTheAutomaticRunNeverClearsAnInstallment() throws Exception {
		InstallmentScenario s = scenario();
		UUID group = groupOf(buy(s, null, null).andExpect(status().isCreated()));

		assertThat(automation.runOnce(LocalDate.of(2030, 1, 1)).cleared()).isZero();

		plan(mvc, s.owner(), group).andExpect(jsonPath("$.clearedCents").value(100_000))
				.andExpect(jsonPath("$.pendingCents").value(200_000));
	}

	@Test
	void cancellingStillExcludesOnlyThePendingInstallmentsWhateverTheirMarker() throws Exception {
		InstallmentScenario s = scenario();
		UUID group = groupOf(buy(s, null, true).andExpect(status().isCreated()));
		automation.runOnce(LocalDate.of(2026, 12, 10));

		// a 1ª e a 2ª estão efetivadas, a 3ª pendente com o marcador
		cancel(mvc, s.owner(), group).andExpect(status().isOk()).andExpect(jsonPath("$.cancelled").value(1));

		plan(mvc, s.owner(), group).andExpect(jsonPath("$.installments.length()").value(2))
				.andExpect(jsonPath("$.clearedCents").value(200_000)).andExpect(jsonPath("$.pendingCents").value(0));
		// excluída, a parcela com marcador nunca mais é efetivada
		assertThat(automation.runOnce(LocalDate.of(2027, 1, 10)).cleared()).isZero();
	}

}
