package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.transaction.PendingScenario.account;
import static com.tostao.tostao_api.transaction.PendingScenario.body;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static com.tostao.tostao_api.transaction.PendingScenario.create;
import static com.tostao.tostao_api.transaction.PendingScenario.dashboard;
import static com.tostao.tostao_api.transaction.PendingScenario.edit;
import static com.tostao.tostao_api.transaction.PendingScenario.list;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsFingerprint;
import static com.tostao.tostao_api.transaction.PendingScenario.transferBody;
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
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * O marcador de efetivação automática (FR-001 a FR-005, SC-001, SC-002): criar, editar, efetivar à mão, uso único e o
 * efeito da execução automática, sempre com o comportamento da spec 009 intacto para quem não liga o marcador.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AutoClearTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

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

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private static String withAutoClear(String json, Object value) {
		return json.substring(0, json.length() - 1) + ",\"autoClear\":" + value + "}";
	}

	private static String editBody(PendingScenario s, long cents, String date, String status, Object autoClear) {
		StringBuilder json = new StringBuilder("{\"accountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"%s\",\"description\":\"Aluguel\",\"categoryId\":\"%s\""
				.formatted(s.checking(), cents, date, s.food()));
		if (status != null) {
			json.append(",\"status\":\"").append(status).append('"');
		}
		if (autoClear != null) {
			json.append(",\"autoClear\":").append(autoClear);
		}
		return json.append('}').toString();
	}

	private UUID idOf(ResultActions result) throws Exception {
		return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
	}

	private boolean markerOf(UUID id) {
		return owner.client().sql("select auto_clear from transactions where id = :id").param("id", id)
				.query(Boolean.class).single();
	}

	private String stateOf(UUID id) {
		return owner.client().sql("select status || '|' || auto_clear from transactions where id = :id").param("id", id)
				.query(String.class).single();
	}

	/** Uma despesa pendente de 80000 em 2026-11-10, criada pela API, com o marcador ligado ou não. */
	private UUID pendingRent(PendingScenario s, Boolean autoClear) throws Exception {
		String json = body("EXPENSE", s.checking(), 80_000, "2026-11-10", "Aluguel", s.food(), "PENDING");
		return idOf(create(mvc, s.owner(), autoClear == null ? json : withAutoClear(json, autoClear))
				.andExpect(status().isCreated()));
	}

	// ---- criar ------------------------------------------------------------------------------------------------

	@Test
	void aPendingExpenseIncomeAndTransferCanBeCreatedWithTheMarker() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());

		create(mvc, s.owner(), withAutoClear(body("EXPENSE", s.checking(), 80_000, "2026-11-20", "Aluguel", s.food(), "PENDING"), true))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.autoClear").value(true));
		create(mvc, s.owner(), withAutoClear(body("INCOME", s.checking(), 30_000, "2026-11-25", "Salario", s.salary(), "PENDING"), true))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.autoClear").value(true));
		UUID leg = idOf(create(mvc, s.owner(), withAutoClear(transferBody(s.checking(), s.savings(), 10_000, "2026-11-28",
				"Reserva", "PENDING"), true)).andExpect(status().isCreated()).andExpect(jsonPath("$.autoClear").value(true)));

		// as duas pontas da transferência têm o marcador
		assertThat(owner.client().sql("select count(*) from transactions where transfer_group_id = "
				+ "(select transfer_group_id from transactions where id = :id) and auto_clear").param("id", leg)
				.query(Long.class).single()).isEqualTo(2L);
	}

	@Test
	void withoutTheMarkerTheTransactionIsCreatedAsBefore() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());

		create(mvc, s.owner(), body("EXPENSE", s.checking(), 1_000, "2026-11-10", "Pendente", s.food(), "PENDING"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.autoClear").value(false));
		create(mvc, s.owner(), body("EXPENSE", s.checking(), 1_000, "2026-11-10", "Efetivada", s.food(), null))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CLEARED"))
				.andExpect(jsonPath("$.autoClear").value(false));
		create(mvc, s.owner(), withAutoClear(body("EXPENSE", s.checking(), 1_000, "2026-11-10", "Desligado", s.food(), "PENDING"), false))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.autoClear").value(false));
	}

	@Test
	void theMarkerOnAClearedTransactionIsRefusedOnItsFieldAndNothingIsCreated() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		String before = transactionsFingerprint(owner, s.owner().workspaceId());

		// sem estado informado o lançamento nasce efetivado
		create(mvc, s.owner(), withAutoClear(body("EXPENSE", s.checking(), 1_000, "2026-11-10", "x", s.food(), null), true))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("autoClear"));
		create(mvc, s.owner(), withAutoClear(body("EXPENSE", s.checking(), 1_000, "2026-11-10", "x", s.food(), "CLEARED"), true))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("autoClear"));
		create(mvc, s.owner(), withAutoClear(transferBody(s.checking(), s.savings(), 1_000, "2026-11-10", "x", null), true))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("autoClear"));
		// valor que não é booleano
		create(mvc, s.owner(), withAutoClear(body("EXPENSE", s.checking(), 1_000, "2026-11-10", "x", s.food(), "PENDING"), "\"sim\""))
				.andExpect(status().isBadRequest());

		assertThat(transactionsFingerprint(owner, s.owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void everyResponseCarriesTheMarkerAndTheOtherTransactionsHaveNoRecurrence() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingRent(s, true);

		mvcGet(s, id).andExpect(status().isOk()).andExpect(jsonPath("$.autoClear").value(true))
				.andExpect(jsonPath("$.recurrenceId").doesNotExist()).andExpect(jsonPath("$.occurrenceNumber").doesNotExist());
		String listed = list(mvc, s.owner(), "size=100").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		assertThat(JsonPath.<List<Boolean>>read(listed, "$.items[*].autoClear")).containsExactlyInAnyOrder(true, false);
		assertThat(JsonPath.<List<Object>>read(listed, "$.items[*].recurrenceId")).containsOnlyNulls();
		assertThat(JsonPath.<List<Object>>read(listed, "$.items[*].occurrenceNumber")).containsOnlyNulls();
	}

	private ResultActions mvcGet(PendingScenario s, UUID id) throws Exception {
		return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.get(PendingScenario.transactionsUrl(s.owner().workspaceId()) + "/" + id)
				.cookie(com.tostao.tostao_api.support.AuthSupport.session(s.owner().token())));
	}

	// ---- editar -----------------------------------------------------------------------------------------------

	@Test
	void theEditSwitchesTheMarkerAndAbsentKeepsIt() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingRent(s, null);
		assertThat(markerOf(id)).isFalse();

		edit(mvc, s.owner(), id, editBody(s, 80_000, "2026-11-10", null, true)).andExpect(status().isOk())
				.andExpect(jsonPath("$.autoClear").value(true)).andExpect(jsonPath("$.status").value("PENDING"));
		edit(mvc, s.owner(), id, editBody(s, 81_000, "2026-11-10", null, null)).andExpect(status().isOk())
				.andExpect(jsonPath("$.autoClear").value(true));
		edit(mvc, s.owner(), id, editBody(s, 81_000, "2026-11-10", null, false)).andExpect(status().isOk())
				.andExpect(jsonPath("$.autoClear").value(false));
		edit(mvc, s.owner(), id, editBody(s, 81_000, "2026-11-10", null, null)).andExpect(status().isOk())
				.andExpect(jsonPath("$.autoClear").value(false));
	}

	@Test
	void editingToClearedDropsTheMarkerAndAskingForBothIsRefused() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingRent(s, true);

		// efetivar pela edição, sem falar do marcador: sai desligado, sem erro
		edit(mvc, s.owner(), id, editBody(s, 80_000, "2026-11-10", "CLEARED", null)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CLEARED")).andExpect(jsonPath("$.autoClear").value(false));
		assertThat(stateOf(id)).isEqualTo("CLEARED|false");

		UUID other = pendingRent(s, true);
		String before = transactionsFingerprint(owner, s.owner().workspaceId());
		// pedir o estado efetivado e o marcador ao mesmo tempo é recusado e nada muda
		edit(mvc, s.owner(), other, editBody(s, 80_000, "2026-11-10", "CLEARED", true)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("autoClear"));
		// e ligar o marcador num lançamento que já está efetivado (estado ausente = o atual) também
		edit(mvc, s.owner(), id, editBody(s, 80_000, "2026-11-10", null, true)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("autoClear"));
		assertThat(transactionsFingerprint(owner, s.owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void aTransferEditKeepsBothLegsWithTheSameMarker() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID leg = idOf(create(mvc, s.owner(), transferBody(s.checking(), s.savings(), 10_000, "2026-11-28", "Reserva", "PENDING"))
				.andExpect(status().isCreated()));
		String transfer = "{\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":10000,\"occurredOn\":\"2026-11-28\",\"description\":\"Reserva\",\"autoClear\":true}"
				.formatted(s.checking(), s.savings());

		edit(mvc, s.owner(), leg, transfer).andExpect(status().isOk()).andExpect(jsonPath("$.autoClear").value(true));

		assertThat(owner.client().sql("select count(*) from transactions where transfer_group_id = "
				+ "(select transfer_group_id from transactions where id = :id) and auto_clear").param("id", leg)
				.query(Long.class).single()).isEqualTo(2L);
	}

	// ---- efetivar à mão e uso único ---------------------------------------------------------------------------

	@Test
	void clearingByHandAMarkedTransactionWorksAndSwitchesTheMarkerOff() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingRent(s, true);

		clear(mvc, s.owner(), id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLEARED"))
				.andExpect(jsonPath("$.autoClear").value(false));

		assertThat(stateOf(id)).isEqualTo("CLEARED|false");
		// repetir não tem efeito (a idempotência da 009 continua)
		clear(mvc, s.owner(), id).andExpect(status().isOk()).andExpect(jsonPath("$.autoClear").value(false));
	}

	@Test
	void clearingByHandOneLegOfAMarkedTransferClearsBothAndDropsBothMarkers() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID leg = idOf(create(mvc, s.owner(), withAutoClear(transferBody(s.checking(), s.savings(), 10_000, "2026-11-28",
				"Reserva", "PENDING"), true)).andExpect(status().isCreated()));

		clear(mvc, s.owner(), leg).andExpect(status().isOk());

		assertThat(owner.client().sql("select string_agg(status || '|' || auto_clear, ',') from transactions where "
				+ "transfer_group_id = (select transfer_group_id from transactions where id = :id)").param("id", leg)
				.query(String.class).single()).isEqualTo("CLEARED|false,CLEARED|false");
	}

	@Test
	void aTransactionGivenBackToPendingStaysPendingForTheAutomaticRun() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingRent(s, true);
		clear(mvc, s.owner(), id).andExpect(status().isOk());

		// a pessoa a devolve a pendente: o marcador não volta sozinho
		edit(mvc, s.owner(), id, editBody(s, 80_000, "2026-11-10", "PENDING", null)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.autoClear").value(false));

		assertThat(automation.runOnce(TODAY.plusDays(30)).cleared()).isZero();
		assertThat(stateOf(id)).isEqualTo("PENDING|false");
	}

	// ---- a execução automática --------------------------------------------------------------------------------

	@Test
	void theAutomaticRunClearsAMarkedTransactionWithTheEffectOfAManualClearAndTheRealizedNumbersFollow() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingRent(s, true);
		UUID unmarked = idOf(create(mvc, s.owner(), body("EXPENSE", s.checking(), 4_000, "2026-11-10", "Sem marcador", s.food(), "PENDING"))
				.andExpect(status().isCreated()));
		UUID future = idOf(create(mvc, s.owner(), withAutoClear(body("EXPENSE", s.checking(), 7_000, "2026-11-20", "Futura", s.food(), "PENDING"), true))
				.andExpect(status().isCreated()));
		String sql = """
				select concat_ws(',', workspace_id, account_id, category_id, type, amount_cents, occurred_on, description,
				                 notes, created_by, created_at, deleted_at) from transactions where id = :id""";
		String rowBefore = owner.client().sql(sql).param("id", id).query(String.class).single();
		String balanceBefore = account(mvc, s.owner(), s.checking()).andReturn().getResponse().getContentAsString();
		assertThat(((Number) JsonPath.read(balanceBefore, "$.balanceCents")).longValue()).isEqualTo(95_000);

		var result = automation.runOnce(TODAY);

		assertThat(result.cleared()).isEqualTo(1);
		assertThat(stateOf(id)).isEqualTo("CLEARED|false");
		assertThat(owner.client().sql(sql).param("id", id).query(String.class).single()).as("nenhum outro campo").isEqualTo(rowBefore);
		assertThat(stateOf(unmarked)).as("sem marcador a data não efetiva").isEqualTo("PENDING|false");
		assertThat(stateOf(future)).as("data futura").isEqualTo("PENDING|true");
		// o saldo e o dashboard passam a contar o efetivado em exatos centavos: 100000 - 5000 - 80000
		String balanceAfter = account(mvc, s.owner(), s.checking()).andReturn().getResponse().getContentAsString();
		assertThat(((Number) JsonPath.read(balanceAfter, "$.balanceCents")).longValue()).isEqualTo(15_000);
		String november = dashboard(mvc, s.owner(), "month=2026-11").andReturn().getResponse().getContentAsString();
		assertThat(((Number) JsonPath.read(november, "$.totals.expenseCents")).longValue()).isEqualTo(85_000);
		assertThat(((Number) JsonPath.read(november, "$.pending.expenseCents")).longValue()).isEqualTo(11_000);
	}

	@Test
	void aViewerCannotTurnTheMarkerOnWhenCreatingOrEditing() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingRent(s, null);
		Fixture viewer = fixtures().addMember(s.owner().workspaceId(), "VIEWER");
		String before = transactionsFingerprint(owner, s.owner().workspaceId());

		create(mvc, viewer, withAutoClear(body("EXPENSE", s.checking(), 1_000, "2026-11-10", "x", s.food(), "PENDING"), true))
				.andExpect(status().isForbidden());
		edit(mvc, viewer, id, editBody(s, 80_000, "2026-11-10", null, true)).andExpect(status().isForbidden());

		assertThat(transactionsFingerprint(owner, s.owner().workspaceId())).isEqualTo(before);
	}

}
