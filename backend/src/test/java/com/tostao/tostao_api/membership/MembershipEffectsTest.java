package com.tostao.tostao_api.membership;

import static com.tostao.tostao_api.membership.MembershipScenario.accept;
import static com.tostao.tostao_api.membership.MembershipScenario.changeRole;
import static com.tostao.tostao_api.membership.MembershipScenario.invite;
import static com.tostao.tostao_api.membership.MembershipScenario.removeMember;
import static com.tostao.tostao_api.membership.MembershipScenario.roleOf;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.automation.AutomationService;
import com.tostao.tostao_api.recurrence.RecurrenceScenario;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;
import com.tostao.tostao_api.transaction.InstallmentScenario;
import com.tostao.tostao_api.transaction.PendingScenario;

/**
 * O efeito nas funcionalidades existentes (FR-014, FR-023, SC-001, SC-005): o papel que o convite dá passa a valer em
 * lançamentos, efetivação, recorrência, compra parcelada e dashboard, mudar o papel e remover valem na requisição
 * seguinte, e os dados de quem sai continuam no workspace.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MembershipEffectsTest {

	private static final LocalDate TODAY = RecurrenceScenario.today();

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

	/** O workspace do dono com uma conta e uma categoria de despesa. */
	private record World(MembershipScenario s, UUID account, UUID category) {

		UUID ws() {
			return s.workspace();
		}
	}

	private World world() {
		MembershipScenario s = MembershipScenario.create(fixtures());
		UUID account = fixtures().insertAccount(s.workspace(), "Corrente", "CHECKING", 500_000, false);
		UUID category = fixtures().insertCategory(s.workspace(), null, "Mercado", "EXPENSE", false);
		return new World(s, account, category);
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	private ResultActions createTransaction(World w, Fixture who, long cents) throws Exception {
		return PendingScenario.create(mvc, who, PendingScenario.body("EXPENSE", w.account(), cents, TODAY.toString(), "Compra", w.category(), null));
	}

	private ResultActions createRecurrence(World w, Fixture who) throws Exception {
		return RecurrenceScenario.createRecurrence(mvc, who, "{\"accountId\":\"%s\",\"categoryId\":\"%s\",\"type\":\"EXPENSE\",\"amountCents\":15000,\"description\":\"Aluguel\",\"frequency\":\"MONTHLY\",\"startOn\":\"%s\"}"
				.formatted(w.account(), w.category(), TODAY));
	}

	private ResultActions purchase(World w, Fixture who) throws Exception {
		return InstallmentScenario.purchase(mvc, who, InstallmentScenario.purchaseBody(w.account(), w.category(), 30_000, 3, TODAY.toString(), "Fone", null));
	}

	/** Todas as escritas das specs anteriores, como a pessoa dada; devolve o status de cada uma. */
	private List<Integer> writes(World w, Fixture who, UUID existingTransaction) throws Exception {
		int create = createTransaction(w, who, 1_000).andReturn().getResponse().getStatus();
		int edit = PendingScenario.edit(mvc, who, existingTransaction, "{\"accountId\":\"%s\",\"amountCents\":2000,\"occurredOn\":\"%s\",\"description\":\"Editada\",\"categoryId\":\"%s\"}"
				.formatted(w.account(), TODAY, w.category())).andReturn().getResponse().getStatus();
		int clear = PendingScenario.clear(mvc, who, existingTransaction).andReturn().getResponse().getStatus();
		int recurrence = createRecurrence(w, who).andReturn().getResponse().getStatus();
		int installments = purchase(w, who).andReturn().getResponse().getStatus();
		return List.of(create, edit, clear, recurrence, installments);
	}

	/** Todas as leituras, como a pessoa dada; devolve o status de cada uma. */
	private List<Integer> reads(World w, Fixture who) throws Exception {
		return List.of(
				PendingScenario.accounts(mvc, who).andReturn().getResponse().getStatus(),
				PendingScenario.list(mvc, who, "size=10").andReturn().getResponse().getStatus(),
				PendingScenario.dashboard(mvc, who, "month=" + TODAY.toString().substring(0, 7)).andReturn().getResponse().getStatus(),
				RecurrenceScenario.listRecurrences(mvc, who).andReturn().getResponse().getStatus(),
				PendingScenario.balanceReport(mvc, who).andReturn().getResponse().getStatus());
	}

	private Fixture joinAs(World w, String role) throws Exception {
		UUID user = fixtures().newUser();
		String email = owner.client().sql("select email from users where id = :id").param("id", user).query(String.class).single();
		Fixture guest = new Fixture(w.ws(), user, fixtures().tokenOf(user));
		String code = JsonPath.read(body(invite(mvc, w.s().owner(), w.ws(), "{\"email\":\"" + email + "\",\"role\":\"" + role + "\"}")
				.andExpect(status().isCreated())), "$.code");
		accept(mvc, guest, "{\"code\":\"" + code + "\"}").andExpect(status().isOk());
		return guest;
	}

	private UUID aTransactionOf(World w) throws Exception {
		return UUID.fromString(JsonPath.read(body(createTransaction(w, w.s().owner(), 5_000).andExpect(status().isCreated())), "$.id"));
	}

	// ---- o papel que o convite dá -----------------------------------------------------------------------------

	@Test
	void anInvitedEditorWritesInEveryExistingFeatureAndAnInvitedViewerOnlyReads() throws Exception {
		World w = world();
		UUID existing = aTransactionOf(w);
		Fixture editor = joinAs(w, "EDITOR");
		Fixture viewer = joinAs(w, "VIEWER");

		assertThat(writes(w, editor, existing)).as("o editor escreve em tudo").containsExactly(201, 200, 200, 201, 201);
		assertThat(reads(w, editor)).containsOnly(200);
		assertThat(writes(w, viewer, existing)).as("o leitor não escreve em nada").containsOnly(403);
		assertThat(reads(w, viewer)).as("o leitor lê tudo").containsOnly(200);
	}

	@Test
	void demotingAnEditorStopsEveryWriteOnTheNextRequestAndPromotingBringsThemBack() throws Exception {
		World w = world();
		UUID existing = aTransactionOf(w);
		Fixture editor = joinAs(w, "EDITOR");
		assertThat(writes(w, editor, existing)).containsExactly(201, 200, 200, 201, 201);

		changeRole(mvc, w.s().owner(), w.ws(), editor.userId(), "VIEWER", true).andExpect(status().isOk());

		// o mesmo cookie, na requisição seguinte: nada de escrever, e as leituras seguem
		assertThat(writes(w, editor, existing)).containsOnly(403);
		assertThat(reads(w, editor)).containsOnly(200);

		changeRole(mvc, w.s().owner(), w.ws(), editor.userId(), "EDITOR", true).andExpect(status().isOk());

		assertThat(createTransaction(w, editor, 1_500).andReturn().getResponse().getStatus()).isEqualTo(201);
	}

	@Test
	void promotingAViewerLetsItWriteOnTheNextRequest() throws Exception {
		World w = world();
		Fixture viewer = joinAs(w, "VIEWER");
		assertThat(createTransaction(w, viewer, 1_000).andReturn().getResponse().getStatus()).isEqualTo(403);

		changeRole(mvc, w.s().owner(), w.ws(), viewer.userId(), "EDITOR", true).andExpect(status().isOk());

		assertThat(createTransaction(w, viewer, 1_000).andReturn().getResponse().getStatus()).isEqualTo(201);
	}

	// ---- remover e sair ---------------------------------------------------------------------------------------

	@Test
	void removingAMemberMakesEveryRouteOfTheWorkspaceNotFoundAtOnce() throws Exception {
		World w = world();
		Fixture editor = joinAs(w, "EDITOR");
		assertThat(reads(w, editor)).containsOnly(200);

		removeMember(mvc, w.s().owner(), w.ws(), editor.userId()).andExpect(status().isNoContent());

		assertThat(reads(w, editor)).as("leituras").containsOnly(404);
		assertThat(writes(w, editor, aTransactionOf(w))).as("escritas").containsOnly(404);
		mvc.perform(get("/api/v1/workspaces/" + w.ws() + "/members").cookie(session(editor.token()))).andExpect(status().isNotFound());
		// mas a sessão segue valendo para o resto do sistema
		mvc.perform(get("/api/v1/me").cookie(session(editor.token()))).andExpect(status().isOk());
	}

	@Test
	void leavingHasTheSameEffectAsBeingRemoved() throws Exception {
		World w = world();
		Fixture editor = joinAs(w, "EDITOR");

		removeMember(mvc, editor, w.ws(), editor.userId()).andExpect(status().isNoContent());

		assertThat(reads(w, editor)).containsOnly(404);
		assertThat(roleOf(owner, w.ws(), editor.userId())).isNull();
	}

	@Test
	void whatARemovedMemberCreatedStaysAndTheRealizedNumbersDoNotChange() throws Exception {
		World w = world();
		Fixture editor = joinAs(w, "EDITOR");
		UUID tx = UUID.fromString(JsonPath.read(body(createTransaction(w, editor, 7_000).andExpect(status().isCreated())), "$.id"));
		String recurrence = body(createRecurrence(w, editor).andExpect(status().isCreated()));
		String recurrenceId = JsonPath.read(recurrence, "$.id");
		String month = "month=" + TODAY.toString().substring(0, 7);
		String accountsBefore = body(PendingScenario.accounts(mvc, w.s().owner()));
		String dashboardBefore = body(PendingScenario.dashboard(mvc, w.s().owner(), month));
		String reportBefore = body(PendingScenario.balanceReport(mvc, w.s().owner()));

		removeMember(mvc, w.s().owner(), w.ws(), editor.userId()).andExpect(status().isNoContent());

		// os dados continuam, com o autor intacto
		assertThat(owner.client().sql("select created_by from transactions where id = :id").param("id", tx).query(UUID.class).single())
				.isEqualTo(editor.userId());
		assertThat(owner.client().sql("select created_by from recurrences where id = cast(:id as uuid)").param("id", recurrenceId)
				.query(UUID.class).single()).isEqualTo(editor.userId());
		assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = cast(:id as uuid) and created_by = :u")
				.param("id", recurrenceId).param("u", editor.userId()).query(Long.class).single()).isGreaterThan(0L);
		// e saldo, dashboard e relatório do dono não mudaram com a saída
		assertThat(body(PendingScenario.accounts(mvc, w.s().owner()))).isEqualTo(accountsBefore);
		assertThat(body(PendingScenario.dashboard(mvc, w.s().owner(), month))).isEqualTo(dashboardBefore);
		assertThat(body(PendingScenario.balanceReport(mvc, w.s().owner()))).isEqualTo(reportBefore);
		// o dono vê a recorrência que o editor criou
		RecurrenceScenario.getRecurrence(mvc, w.s().owner(), recurrenceId).andExpect(status().isOk());
	}

	@Test
	void theAutomaticRunKeepsGeneratingForARecurrenceCreatedByWhoLeft() throws Exception {
		World w = world();
		Fixture editor = joinAs(w, "EDITOR");
		String recurrenceId = JsonPath.read(body(createRecurrence(w, editor).andExpect(status().isCreated())), "$.id");
		long before = owner.client().sql("select count(*) from transactions where recurrence_id = cast(:id as uuid)").param("id", recurrenceId)
				.query(Long.class).single();
		removeMember(mvc, w.s().owner(), w.ws(), editor.userId()).andExpect(status().isNoContent());

		automation.runOnce(TODAY.plusMonths(6));

		long after = owner.client().sql("select count(*) from transactions where recurrence_id = cast(:id as uuid)").param("id", recurrenceId)
				.query(Long.class).single();
		assertThat(after).isGreaterThan(before);
		assertThat(owner.client().sql("select count(*) from transactions where recurrence_id = cast(:id as uuid) and created_by <> :u")
				.param("id", recurrenceId).param("u", editor.userId()).query(Long.class).single()).as("o autor das novas é o da recorrência").isZero();
	}

	@Test
	void aRemovedPersonReinvitedComesBackWithTheNewRole() throws Exception {
		World w = world();
		Fixture editor = joinAs(w, "EDITOR");
		String email = owner.client().sql("select email from users where id = :id").param("id", editor.userId()).query(String.class).single();
		removeMember(mvc, w.s().owner(), w.ws(), editor.userId()).andExpect(status().isNoContent());
		assertThat(reads(w, editor)).containsOnly(404);

		String code = JsonPath.read(body(invite(mvc, w.s().owner(), w.ws(), "{\"email\":\"" + email + "\",\"role\":\"VIEWER\"}")
				.andExpect(status().isCreated())), "$.code");
		accept(mvc, editor, "{\"code\":\"" + code + "\"}").andExpect(status().isOk());

		assertThat(roleOf(owner, w.ws(), editor.userId())).isEqualTo("VIEWER");
		assertThat(reads(w, editor)).containsOnly(200);
		assertThat(createTransaction(w, editor, 1_000).andReturn().getResponse().getStatus()).isEqualTo(403);
	}

	// ---- isolamento entre os workspaces da mesma pessoa -------------------------------------------------------

	@Test
	void beingAViewerInAnotherWorkspaceGivesNothingInTheOwnOne() throws Exception {
		World a = world();
		World b = world();
		// o dono de B é convidado para A como leitor
		String email = owner.client().sql("select email from users where id = :id").param("id", b.s().owner().userId()).query(String.class).single();
		String code = JsonPath.read(body(invite(mvc, a.s().owner(), a.ws(), "{\"email\":\"" + email + "\",\"role\":\"VIEWER\"}")
				.andExpect(status().isCreated())), "$.code");
		accept(mvc, b.s().owner(), "{\"code\":\"" + code + "\"}").andExpect(status().isOk());
		Fixture inA = new Fixture(a.ws(), b.s().owner().userId(), b.s().owner().token());

		// em A é só leitor; no próprio workspace B continua dono e escreve
		assertThat(createTransaction(a, inA, 1_000).andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(reads(a, inA)).containsOnly(200);
		assertThat(createTransaction(b, b.s().owner(), 1_000).andReturn().getResponse().getStatus()).isEqualTo(201);
		assertThat(roleOf(owner, b.ws(), b.s().owner().userId())).isEqualTo("OWNER");
		// e não vê nada de B através de A: a conta de B não existe em A
		assertThat(createTransaction(new World(a.s(), b.account(), b.category()), a.s().owner(), 1_000).andReturn().getResponse().getStatus())
				.as("conta e categoria de B não servem em A").isEqualTo(404);
	}

	@Test
	void theTransactionRoutesOfTheFormerMemberDoNotLeakThroughDirectIds() throws Exception {
		World w = world();
		UUID tx = aTransactionOf(w);
		Fixture editor = joinAs(w, "EDITOR");
		removeMember(mvc, w.s().owner(), w.ws(), editor.userId()).andExpect(status().isNoContent());
		String base = "/api/v1/workspaces/" + w.ws() + "/transactions/" + tx;

		mvc.perform(get(base).cookie(session(editor.token()))).andExpect(status().isNotFound());
		String edit = "{\"accountId\":\"%s\",\"amountCents\":2000,\"occurredOn\":\"%s\",\"description\":\"Editada\",\"categoryId\":\"%s\"}".formatted(w.account(), TODAY, w.category());
		mvc.perform(put(base).cookie(session(editor.token())).contentType(MediaType.APPLICATION_JSON).content(edit)).andExpect(status().isNotFound());
		mvc.perform(delete(base).cookie(session(editor.token()))).andExpect(status().isNotFound());
		assertThat(owner.client().sql("select deleted_at is null from transactions where id = :id").param("id", tx).query(Boolean.class).single()).isTrue();
	}

}
