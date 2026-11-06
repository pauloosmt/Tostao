package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UpdateTransactionTest {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 5);

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private String base(UUID workspaceId) {
		return "/api/v1/workspaces/" + workspaceId + "/transactions";
	}

	private MvcResult edit(Fixture who, UUID workspaceId, UUID id, String json) throws Exception {
		return mvc.perform(put(base(workspaceId) + "/" + id).cookie(session(who.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
	}

	private MvcResult edit(World s, UUID id, String json) throws Exception {
		return edit(s.fx(), s.fx().workspaceId(), id, json);
	}

	private void assertInvalid(World s, UUID id, String json, String field) throws Exception {
		MvcResult result = edit(s, id, json);
		assertThat(result.getResponse().getStatus()).as(json).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).as(json).contains("\"field\":\"" + field + "\"");
	}

	private static String ledger(UUID account, long cents, String date, String description, UUID category) {
		return "{\"accountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"%s\",\"description\":\"%s\",\"categoryId\":%s}"
				.formatted(account, cents, date, description, category == null ? "null" : "\"" + category + "\"");
	}

	private static String transfer(UUID from, UUID to, long cents, String date, String description) {
		return "{\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"%s\",\"description\":\"%s\"}"
				.formatted(from, to, cents, date, description);
	}

	private long balance(World s, UUID account) throws Exception {
		String json = mvc.perform(get("/api/v1/workspaces/" + s.fx().workspaceId() + "/accounts/" + account)
				.cookie(session(s.fx().token()))).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(json, "$.balanceCents")).longValue();
	}

	private Map<String, Object> row(UUID id) {
		return owner.client().sql("""
				select account_id, category_id, amount_cents, occurred_on, description, notes, created_by, created_at,
				       updated_at, deleted_at
				from transactions where id = :id""").param("id", id).query().singleRow();
	}

	/**
	 * Conta A (100.000), B (0) e um cartão; uma despesa (A, 10.000), uma receita (A, 5.000) e uma transferência
	 * A→B de 3.000.
	 */
	private record World(Fixture fx, UUID a, UUID b, UUID card, UUID food, UUID leisure, UUID salary, UUID expense,
			UUID income, UUID out, UUID in) {
	}

	private World world() {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID a = fixtures().insertAccount(w, "A", "CHECKING", 100_000, false);
		UUID b = fixtures().insertAccount(w, "B", "SAVINGS", 0, false);
		UUID card = fixtures().insertAccount(w, "Cartao", "CREDIT_CARD", 0, false);
		UUID food = fixtures().insertCategory(w, null, "Comida", "EXPENSE", false);
		UUID leisure = fixtures().insertCategory(w, null, "Lazer", "EXPENSE", false);
		UUID salary = fixtures().insertCategory(w, null, "Salario", "INCOME", false);
		UUID expense = fixtures().insertLedgerTransaction(w, a, fx.userId(), food, "EXPENSE", 10_000, DAY, "Mercado",
				false);
		UUID income = fixtures().insertLedgerTransaction(w, a, fx.userId(), salary, "INCOME", 5_000, DAY, "Pagamento",
				false);
		UUID[] pair = fixtures().insertTransferPair(w, a, b, fx.userId(), 3_000, DAY, "Reserva");
		return new World(fx, a, b, card, food, leisure, salary, expense, income, pair[0], pair[1]);
	}

	@Test
	void changingTheAmountMovesTheBalanceByTheDifference() throws Exception {
		World s = world();
		assertThat(balance(s, s.a())).isEqualTo(92_000);

		MvcResult result = edit(s, s.expense(), ledger(s.a(), 4_000, "2026-11-05", "Mercado", s.food()));

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.amountCents")).longValue())
				.isEqualTo(-4_000);
		assertThat(balance(s, s.a())).isEqualTo(98_000);
	}

	@Test
	void changingTheAccountMovesTheBalanceOfBoth() throws Exception {
		World s = world();

		assertThat(edit(s, s.expense(), ledger(s.b(), 10_000, "2026-11-05", "Mercado", s.food())).getResponse()
				.getStatus()).isEqualTo(200);

		assertThat(balance(s, s.a())).isEqualTo(102_000);
		assertThat(balance(s, s.b())).isEqualTo(3_000 - 10_000);
	}

	@Test
	void dateDescriptionNotesAndCategoryChangeAndTheAuthorStays() throws Exception {
		World s = world();
		Map<String, Object> before = row(s.expense());
		String json = "{\"accountId\":\"%s\",\"amountCents\":10000,\"occurredOn\":\"2026-12-24\",\"description\":\"  Cinema  \",\"notes\":\"com pipoca\",\"categoryId\":\"%s\"}"
				.formatted(s.a(), s.leisure());

		MvcResult result = edit(s, s.expense(), json);

		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		String body = result.getResponse().getContentAsString();
		assertThat((String) JsonPath.read(body, "$.occurredOn")).isEqualTo("2026-12-24");
		assertThat((String) JsonPath.read(body, "$.description")).isEqualTo("Cinema");
		assertThat((String) JsonPath.read(body, "$.notes")).isEqualTo("com pipoca");
		assertThat((String) JsonPath.read(body, "$.categoryId")).isEqualTo(s.leisure().toString());
		Map<String, Object> after = row(s.expense());
		assertThat(after.get("created_by")).isEqualTo(before.get("created_by")).isEqualTo(s.fx().userId());
		assertThat(after.get("created_at")).isEqualTo(before.get("created_at"));
		assertThat(((java.sql.Timestamp) after.get("updated_at")).toInstant())
				.isAfter(((java.sql.Timestamp) before.get("updated_at")).toInstant());
		assertThat(body).doesNotContain("workspace");
	}

	@Test
	void anAbsentNotesValueClearsTheNotes() throws Exception {
		World s = world();
		edit(s, s.expense(), "{\"accountId\":\"%s\",\"amountCents\":1,\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"notes\":\"obs\",\"categoryId\":\"%s\"}"
				.formatted(s.a(), s.food()));

		edit(s, s.expense(), ledger(s.a(), 1, "2026-11-05", "x", s.food()));

		assertThat(row(s.expense()).get("notes")).isNull();
	}

	@Test
	void theTypeCanBeEchoedButNeverChanged() throws Exception {
		World s = world();
		String same = "{\"type\":\"EXPENSE\",\"accountId\":\"%s\",\"amountCents\":10000,\"occurredOn\":\"2026-11-05\",\"description\":\"Mercado\",\"categoryId\":\"%s\"}"
				.formatted(s.a(), s.food());

		assertThat(edit(s, s.expense(), same).getResponse().getStatus()).isEqualTo(200);
		assertInvalid(s, s.expense(), same.replace("EXPENSE", "INCOME"), "type");
		assertInvalid(s, s.expense(), same.replace("EXPENSE", "TRANSFER"), "type");
		assertInvalid(s, s.out(), same.replace("EXPENSE", "EXPENSE"), "type");
	}

	@Test
	void theSameValidationsAsCreationApply() throws Exception {
		World s = world();
		UUID id = s.expense();

		for (long cents : new long[] { 0, -5, 1_000_000_000_001L }) {
			assertInvalid(s, id, ledger(s.a(), cents, "2026-11-05", "x", s.food()), "amountCents");
		}
		assertInvalid(s, id, ledger(s.a(), 100, "2026-11-05", "", s.food()), "description");
		assertInvalid(s, id, ledger(s.a(), 100, "2026-11-05", "x".repeat(201), s.food()), "description");
		assertInvalid(s, id, ledger(s.a(), 100, "1999-12-31", "x", s.food()), "occurredOn");
		assertInvalid(s, id, ledger(s.a(), 100, "2026-11-05", "x", null), "categoryId");
		assertInvalid(s, id, ledger(s.a(), 100, "2026-11-05", "x", s.salary()), "categoryId");
		assertInvalid(s, s.income(), ledger(s.a(), 100, "2026-11-05", "x", s.food()), "categoryId");
		assertInvalid(s, id, "{\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":100,\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"categoryId\":\"%s\"}"
				.formatted(s.a(), s.b(), s.food()), "destinationAccountId");
		assertInvalid(s, id, "{\"amountCents\":100,\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"categoryId\":\"%s\"}"
				.formatted(s.food()), "accountId");
		for (String extra : new String[] { "\"createdBy\":\"" + UUID.randomUUID() + "\"",
				"\"workspaceId\":\"" + UUID.randomUUID() + "\"", "\"transferGroupId\":\"" + UUID.randomUUID() + "\"",
				"\"deletedAt\":\"2020-01-01T00:00:00Z\"" }) {
			String json = ledger(s.a(), 100, "2026-11-05", "x", s.food());
			assertThat(edit(s, id, json.substring(0, json.length() - 1) + "," + extra + "}").getResponse().getStatus())
					.as(extra).isEqualTo(400);
		}
		Map<String, Object> unchanged = row(id);
		assertThat(((Number) unchanged.get("amount_cents")).longValue()).isEqualTo(-10_000);
		assertThat(unchanged.get("description")).isEqualTo("Mercado");
	}

	@Test
	void editingEitherLegChangesBothLegsTogether() throws Exception {
		World s = world();

		MvcResult viaInflow = edit(s, s.in(), transfer(s.a(), s.b(), 4_500, "2026-11-10", "Nova reserva"));

		assertThat(viaInflow.getResponse().getStatus()).isEqualTo(200);
		String body = viaInflow.getResponse().getContentAsString();
		assertThat((String) JsonPath.read(body, "$.id")).isEqualTo(s.in().toString());
		assertThat(((Number) JsonPath.read(body, "$.amountCents")).longValue()).isEqualTo(4_500);
		assertThat((String) JsonPath.read(body, "$.accountId")).isEqualTo(s.b().toString());
		assertThat((String) JsonPath.read(body, "$.counterpartAccountId")).isEqualTo(s.a().toString());
		assertCoherent(s, s.a(), s.b(), 4_500, "2026-11-10", "Nova reserva");

		MvcResult viaOutflow = edit(s, s.out(), transfer(s.a(), s.card(), 2_000, "2026-11-11", "Para o cartao"));

		assertThat(viaOutflow.getResponse().getStatus()).isEqualTo(200);
		assertThat((String) JsonPath.read(viaOutflow.getResponse().getContentAsString(), "$.id"))
				.isEqualTo(s.out().toString());
		assertCoherent(s, s.a(), s.card(), 2_000, "2026-11-11", "Para o cartao");
		assertThat(balance(s, s.b())).isZero();
		assertThat(balance(s, s.card())).isEqualTo(2_000);
	}

	private void assertCoherent(World s, UUID from, UUID to, long cents, String date, String description) {
		Map<String, Object> out = row(s.out());
		Map<String, Object> in = row(s.in());
		assertThat(out.get("account_id")).isEqualTo(from);
		assertThat(in.get("account_id")).isEqualTo(to);
		assertThat(((Number) out.get("amount_cents")).longValue()).isEqualTo(-cents);
		assertThat(((Number) in.get("amount_cents")).longValue()).isEqualTo(cents);
		for (Map<String, Object> leg : List.of(out, in)) {
			assertThat(leg.get("occurred_on").toString()).isEqualTo(date);
			assertThat(leg.get("description")).isEqualTo(description);
			assertThat(leg.get("category_id")).isNull();
			assertThat(leg.get("deleted_at")).isNull();
		}
	}

	@Test
	void aTransferEditKeepsTheTwoAccountsDifferentAndWithoutCategory() throws Exception {
		World s = world();

		assertInvalid(s, s.out(), transfer(s.a(), s.a(), 100, "2026-11-05", "x"), "destinationAccountId");
		assertInvalid(s, s.in(), transfer(s.b(), s.b(), 100, "2026-11-05", "x"), "destinationAccountId");
		assertInvalid(s, s.out(), ledger(s.a(), 100, "2026-11-05", "x", null), "destinationAccountId");
		assertInvalid(s, s.out(), "{\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":100,\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"categoryId\":\"%s\"}"
				.formatted(s.a(), s.b(), s.food()), "categoryId");
		assertCoherent(s, s.a(), s.b(), 3_000, "2026-11-05", "Reserva");
	}

	@Test
	void swappingOriginAndDestinationFlipsTheLegs() throws Exception {
		World s = world();

		assertThat(edit(s, s.out(), transfer(s.b(), s.a(), 3_000, "2026-11-05", "Reserva")).getResponse().getStatus())
				.isEqualTo(200);

		assertCoherent(s, s.b(), s.a(), 3_000, "2026-11-05", "Reserva");
		assertThat(balance(s, s.a())).isEqualTo(98_000);
		assertThat(balance(s, s.b())).isEqualTo(-3_000);
	}

	@Test
	void usingAnArchivedAccountOrCategoryIsRefusedButKeepingOneIsAccepted() throws Exception {
		World s = world();
		UUID w = s.fx().workspaceId();
		UUID archivedAccount = fixtures().insertAccount(w, "Velha", "CHECKING", 0, true);
		UUID archivedCategory = fixtures().insertCategory(w, null, "Antiga", "EXPENSE", true);
		UUID otherArchivedCategory = fixtures().insertCategory(w, null, "Antiga 2", "EXPENSE", true);
		UUID old = fixtures().insertLedgerTransaction(w, archivedAccount, s.fx().userId(), archivedCategory, "EXPENSE",
				700, DAY, "Antigo", false);
		UUID[] oldTransfer = fixtures().insertTransferPair(w, archivedAccount, s.b(), s.fx().userId(), 900, DAY,
				"Transferencia antiga");

		// passar a usar uma conta ou categoria arquivada é recusado
		assertThat(edit(s, s.expense(), ledger(archivedAccount, 100, "2026-11-05", "x", s.food())).getResponse()
				.getStatus()).isEqualTo(409);
		assertThat(edit(s, s.expense(), ledger(s.a(), 100, "2026-11-05", "x", archivedCategory)).getResponse()
				.getStatus()).isEqualTo(409);
		assertThat(edit(s, s.out(), transfer(archivedAccount, s.b(), 100, "2026-11-05", "x")).getResponse()
				.getStatus()).isEqualTo(409);
		assertThat(edit(s, s.out(), transfer(s.a(), archivedAccount, 100, "2026-11-05", "x")).getResponse()
				.getStatus()).isEqualTo(409);
		assertThat(edit(s, old, ledger(archivedAccount, 700, "2026-11-05", "x", otherArchivedCategory)).getResponse()
				.getStatus()).isEqualTo(409);
		assertCoherent(s, s.a(), s.b(), 3_000, "2026-11-05", "Reserva");

		// manter a conta e a categoria arquivadas que já tinha é aceito
		assertThat(edit(s, old, ledger(archivedAccount, 800, "2026-11-06", "Antigo corrigido", archivedCategory))
				.getResponse().getStatus()).isEqualTo(200);
		assertThat(edit(s, oldTransfer[1], transfer(archivedAccount, s.b(), 1_000, "2026-11-06", "Corrigida"))
				.getResponse().getStatus()).isEqualTo(200);
		assertThat(((Number) row(old).get("amount_cents")).longValue()).isEqualTo(-800);
		assertThat(((Number) row(oldTransfer[0]).get("amount_cents")).longValue()).isEqualTo(-1_000);
		assertThat(((Number) row(oldTransfer[1]).get("amount_cents")).longValue()).isEqualTo(1_000);
	}

	@Test
	void accountsAndCategoriesOfAnotherWorkspaceOrMissingAreNotFound() throws Exception {
		World s = world();
		Fixture other = fixtures().workspaceWithOwner();
		UUID foreignAccount = fixtures().insertAccount(other.workspaceId(), "Alheia", "CHECKING", 0, false);
		UUID foreignCategory = fixtures().insertCategory(other.workspaceId(), null, "Alheia", "EXPENSE", false);

		assertThat(edit(s, s.expense(), ledger(foreignAccount, 100, "2026-11-05", "x", s.food())).getResponse()
				.getStatus()).isEqualTo(404);
		assertThat(edit(s, s.expense(), ledger(s.a(), 100, "2026-11-05", "x", foreignCategory)).getResponse()
				.getStatus()).isEqualTo(404);
		assertThat(edit(s, s.expense(), ledger(UUID.randomUUID(), 100, "2026-11-05", "x", s.food())).getResponse()
				.getStatus()).isEqualTo(404);
		assertThat(edit(s, s.out(), transfer(s.a(), foreignAccount, 100, "2026-11-05", "x")).getResponse().getStatus())
				.isEqualTo(404);
		assertCoherent(s, s.a(), s.b(), 3_000, "2026-11-05", "Reserva");
	}

	@Test
	void deletedForeignMissingOrMalformedIdsAreNotFound() throws Exception {
		World s = world();
		World other = world();
		UUID deleted = fixtures().insertLedgerTransaction(s.fx().workspaceId(), s.a(), s.fx().userId(), s.food(),
				"EXPENSE", 100, DAY, "Excluido", true);
		String body = ledger(s.a(), 100, "2026-11-05", "x", s.food());

		for (UUID id : new UUID[] { deleted, other.expense(), UUID.randomUUID() }) {
			assertThat(edit(s, id, body).getResponse().getStatus()).as(id.toString()).isEqualTo(404);
		}
		mvc.perform(put(base(s.fx().workspaceId()) + "/abc").cookie(session(s.fx().token()))
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
		assertThat(row(other.expense()).get("description")).isEqualTo("Mercado");
	}

	@Test
	void rolesAndMembershipDecideWhoCanEdit() throws Exception {
		World s = world();
		UUID w = s.fx().workspaceId();
		Fixture viewer = fixtures().addMember(w, "VIEWER");
		Fixture editor = fixtures().addMember(w, "EDITOR");
		Fixture stranger = fixtures().workspaceWithOwner();
		String body = ledger(s.a(), 100, "2026-11-05", "Mudada", s.food());

		assertThat(edit(viewer, w, s.expense(), body).getResponse().getStatus()).isEqualTo(403);
		MvcResult asStranger = edit(stranger, w, s.expense(), body);
		MvcResult missing = edit(stranger, UUID.randomUUID(), s.expense(), body);
		assertThat(asStranger.getResponse().getStatus()).isEqualTo(404);
		assertThat(asStranger.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", ""))
				.isEqualTo(missing.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", ""));
		assertThat(row(s.expense()).get("description")).isEqualTo("Mercado");

		// o EDITOR edita o que o dono criou, e o autor não muda
		assertThat(edit(editor, w, s.expense(), body).getResponse().getStatus()).isEqualTo(200);
		assertThat(row(s.expense()).get("created_by")).isEqualTo(s.fx().userId());
		assertThat(edit(s.fx(), w, s.income(), ledger(s.a(), 200, "2026-11-05", "Dono", s.salary())).getResponse()
				.getStatus()).isEqualTo(200);
		mvc.perform(put(base(w) + "/" + s.expense()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isUnauthorized());
	}

}
