package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
class CreateTransactionTest {

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

	private MvcResult create(Fixture fx, String json) throws Exception {
		return mvc.perform(post(base(fx.workspaceId())).cookie(session(fx.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
	}

	private static String body(String type, UUID account, long cents, String date, String description, UUID category) {
		return "{\"type\":\"%s\",\"accountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"%s\",\"description\":\"%s\",\"categoryId\":%s}"
				.formatted(type, account, cents, date, description, category == null ? "null" : "\"" + category + "\"");
	}

	private void assertInvalid(Fixture fx, String json, String field) throws Exception {
		MvcResult result = create(fx, json);
		assertThat(result.getResponse().getStatus()).as(json).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).as(json).contains("\"field\":\"" + field + "\"");
	}

	private long count(UUID workspaceId) {
		return owner.client().sql("select count(*) from transactions where workspace_id = :w").param("w", workspaceId)
				.query(Long.class).single();
	}

	private long balance(Fixture fx, UUID account) throws Exception {
		String json = mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/accounts/" + account)
				.cookie(session(fx.token()))).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(json, "$.balanceCents")).longValue();
	}

	/** Workspace com uma conta de R$ 1.000,00 e uma categoria de despesa e uma de receita. */
	private record Setup(Fixture fx, UUID account, UUID expenseCategory, UUID incomeCategory) {
	}

	private Setup setup() {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID account = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 100_000, false);
		UUID expense = fixtures().insertCategory(fx.workspaceId(), null, "Mercado", "EXPENSE", false);
		UUID income = fixtures().insertCategory(fx.workspaceId(), null, "Salario", "INCOME", false);
		return new Setup(fx, account, expense, income);
	}

	@Test
	void anExpenseIsStoredNegativeAndLowersTheBalance() throws Exception {
		Setup s = setup();

		MvcResult result = create(s.fx(), body("EXPENSE", s.account(), 10_000, "2026-11-05", "Mercado", s.expenseCategory()));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		String json = result.getResponse().getContentAsString();
		String id = JsonPath.read(json, "$.id");
		assertThat(result.getResponse().getHeader("Location")).isEqualTo(base(s.fx().workspaceId()) + "/" + id);
		assertThat(((Number) JsonPath.read(json, "$.amountCents")).longValue()).isEqualTo(-10_000);
		assertThat((String) JsonPath.read(json, "$.type")).isEqualTo("EXPENSE");
		assertThat((String) JsonPath.read(json, "$.occurredOn")).isEqualTo("2026-11-05");
		assertThat((String) JsonPath.read(json, "$.createdBy")).isEqualTo(s.fx().userId().toString());
		assertThat((String) JsonPath.read(json, "$.categoryId")).isEqualTo(s.expenseCategory().toString());
		assertThat(json).contains("\"createdAt\"").contains("\"updatedAt\"").doesNotContain("workspace");
		assertThat(balance(s.fx(), s.account())).isEqualTo(90_000);
		assertThat(owner.client().sql("select amount_cents from transactions where id = :id")
				.param("id", UUID.fromString(id)).query(Long.class).single()).isEqualTo(-10_000);
	}

	@Test
	void anIncomeIsStoredPositiveAndRaisesTheBalance() throws Exception {
		Setup s = setup();
		create(s.fx(), body("EXPENSE", s.account(), 10_000, "2026-11-05", "Mercado", s.expenseCategory()));

		MvcResult result = create(s.fx(), body("INCOME", s.account(), 25_000, "2026-11-05", "Salario", s.incomeCategory()));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat(((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.amountCents")).longValue())
				.isEqualTo(25_000);
		assertThat(balance(s.fx(), s.account())).isEqualTo(115_000);
	}

	@Test
	void centsAddUpExactly() throws Exception {
		Setup s = setup();

		create(s.fx(), body("INCOME", s.account(), 10, "2026-11-05", "a", s.incomeCategory()));
		create(s.fx(), body("INCOME", s.account(), 20, "2026-11-05", "b", s.incomeCategory()));

		assertThat(balance(s.fx(), s.account())).isEqualTo(100_030);
	}

	@Test
	void aMainCategoryAndASubcategoryAreBothAccepted() throws Exception {
		Setup s = setup();
		UUID sub = fixtures().insertCategory(s.fx().workspaceId(), s.expenseCategory(), "Feira", "EXPENSE", false);

		assertThat(create(s.fx(), body("EXPENSE", s.account(), 100, "2026-11-05", "x", s.expenseCategory()))
				.getResponse().getStatus()).isEqualTo(201);
		assertThat(create(s.fx(), body("EXPENSE", s.account(), 100, "2026-11-05", "y", sub)).getResponse().getStatus())
				.isEqualTo(201);
	}

	@Test
	void invalidAmountsAreRejected() throws Exception {
		Setup s = setup();

		for (long cents : new long[] { 0, -5, 1_000_000_000_001L }) {
			assertInvalid(s.fx(), body("EXPENSE", s.account(), cents, "2026-11-05", "x", s.expenseCategory()),
					"amountCents");
		}
		String decimal = "{\"type\":\"EXPENSE\",\"accountId\":\"%s\",\"amountCents\":10.5,\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"categoryId\":\"%s\"}"
				.formatted(s.account(), s.expenseCategory());
		assertThat(create(s.fx(), decimal).getResponse().getStatus()).isEqualTo(400);
		assertThat(create(s.fx(), body("EXPENSE", s.account(), 1_000_000_000_000L, "2026-11-05", "limite", s.expenseCategory()))
				.getResponse().getStatus()).isEqualTo(201);
		assertThat(count(s.fx().workspaceId())).isEqualTo(1);
	}

	@Test
	void invalidDescriptionsAndDatesAreRejected() throws Exception {
		Setup s = setup();

		assertInvalid(s.fx(), body("EXPENSE", s.account(), 100, "2026-11-05", "", s.expenseCategory()), "description");
		assertInvalid(s.fx(), body("EXPENSE", s.account(), 100, "2026-11-05", "   ", s.expenseCategory()),
				"description");
		assertInvalid(s.fx(), body("EXPENSE", s.account(), 100, "2026-11-05", "x".repeat(201), s.expenseCategory()),
				"description");
		assertInvalid(s.fx(), body("EXPENSE", s.account(), 100, "1999-12-31", "x", s.expenseCategory()), "occurredOn");
		assertInvalid(s.fx(), body("EXPENSE", s.account(), 100, "2101-01-01", "x", s.expenseCategory()), "occurredOn");
		assertThat(create(s.fx(), body("EXPENSE", s.account(), 100, "2026-02-30", "x", s.expenseCategory()))
				.getResponse().getStatus()).isEqualTo(400);
		assertInvalid(s.fx(), "{\"type\":\"EXPENSE\",\"accountId\":\"%s\",\"amountCents\":100,\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"notes\":\"%s\",\"categoryId\":\"%s\"}"
				.formatted(s.account(), "n".repeat(1001), s.expenseCategory()), "notes");
		assertThat(count(s.fx().workspaceId())).isZero();
	}

	@Test
	void theCategoryIsRequiredAndMustHaveTheSameTypeAsTheTransaction() throws Exception {
		Setup s = setup();

		assertInvalid(s.fx(), body("EXPENSE", s.account(), 100, "2026-11-05", "x", null), "categoryId");
		assertInvalid(s.fx(), body("INCOME", s.account(), 100, "2026-11-05", "x", null), "categoryId");
		assertInvalid(s.fx(), body("EXPENSE", s.account(), 100, "2026-11-05", "x", s.incomeCategory()), "categoryId");
		assertInvalid(s.fx(), body("INCOME", s.account(), 100, "2026-11-05", "x", s.expenseCategory()), "categoryId");
		assertThat(count(s.fx().workspaceId())).isZero();
	}

	@Test
	void aDestinationAccountOnlyMakesSenseForTransfers() throws Exception {
		Setup s = setup();
		UUID other = fixtures().insertAccount(s.fx().workspaceId(), "Poupanca", "SAVINGS", 0, false);

		assertInvalid(s.fx(), "{\"type\":\"INCOME\",\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":100,\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"categoryId\":\"%s\"}"
				.formatted(s.account(), other, s.incomeCategory()), "destinationAccountId");
		assertThat(count(s.fx().workspaceId())).isZero();
	}

	@Test
	void unknownTypesAndFieldsOutsideTheListAreRejected() throws Exception {
		Setup s = setup();
		String valid = body("EXPENSE", s.account(), 100, "2026-11-05", "x", s.expenseCategory());

		assertThat(create(s.fx(), valid.replace("EXPENSE", "OUTRO")).getResponse().getStatus()).isEqualTo(400);
		for (String extra : new String[] { "\"workspaceId\":\"" + UUID.randomUUID() + "\"",
				"\"createdBy\":\"" + UUID.randomUUID() + "\"", "\"transferGroupId\":\"" + UUID.randomUUID() + "\"",
				"\"deletedAt\":\"2020-01-01T00:00:00Z\"", "\"createdAt\":\"2020-01-01T00:00:00Z\"",
				"\"status\":\"OUTRO\"" }) {
			String json = valid.substring(0, valid.length() - 1) + "," + extra + "}";
			assertThat(create(s.fx(), json).getResponse().getStatus()).as(extra).isEqualTo(400);
		}
		assertThat(count(s.fx().workspaceId())).isZero();
	}

	@Test
	void theDescriptionIsStoredWithoutSurroundingSpacesAndFutureDatesAreAccepted() throws Exception {
		Setup s = setup();

		MvcResult result = create(s.fx(), body("EXPENSE", s.account(), 100, "2099-01-01", "  Futuro  ", s.expenseCategory()));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat((String) JsonPath.read(result.getResponse().getContentAsString(), "$.description"))
				.isEqualTo("Futuro");
	}

	@Test
	void identicalTransactionsAreAllowed() throws Exception {
		Setup s = setup();
		String json = body("EXPENSE", s.account(), 500, "2026-11-05", "Cafe", s.expenseCategory());

		assertThat(create(s.fx(), json).getResponse().getStatus()).isEqualTo(201);
		assertThat(create(s.fx(), json).getResponse().getStatus()).isEqualTo(201);
		assertThat(balance(s.fx(), s.account())).isEqualTo(99_000);
	}

	@Test
	void anArchivedAccountOrCategoryCannotReceiveNewTransactions() throws Exception {
		Setup s = setup();
		UUID archivedAccount = fixtures().insertAccount(s.fx().workspaceId(), "Velha", "CHECKING", 0, true);
		UUID archivedCategory = fixtures().insertCategory(s.fx().workspaceId(), null, "Velha", "EXPENSE", true);

		assertThat(create(s.fx(), body("EXPENSE", archivedAccount, 100, "2026-11-05", "x", s.expenseCategory()))
				.getResponse().getStatus()).isEqualTo(409);
		assertThat(create(s.fx(), body("EXPENSE", s.account(), 100, "2026-11-05", "x", archivedCategory))
				.getResponse().getStatus()).isEqualTo(409);
		assertThat(count(s.fx().workspaceId())).isZero();
	}

	@Test
	void anAccountOrCategoryOfAnotherWorkspaceOrMissingIsNotFound() throws Exception {
		Setup s = setup();
		Fixture other = fixtures().workspaceWithOwner();
		UUID foreignAccount = fixtures().insertAccount(other.workspaceId(), "Alheia", "CHECKING", 0, false);
		UUID foreignCategory = fixtures().insertCategory(other.workspaceId(), null, "Alheia", "EXPENSE", false);

		assertThat(create(s.fx(), body("EXPENSE", foreignAccount, 100, "2026-11-05", "x", s.expenseCategory()))
				.getResponse().getStatus()).isEqualTo(404);
		assertThat(create(s.fx(), body("EXPENSE", UUID.randomUUID(), 100, "2026-11-05", "x", s.expenseCategory()))
				.getResponse().getStatus()).isEqualTo(404);
		assertThat(create(s.fx(), body("EXPENSE", s.account(), 100, "2026-11-05", "x", foreignCategory))
				.getResponse().getStatus()).isEqualTo(404);
		assertThat(create(s.fx(), body("EXPENSE", s.account(), 100, "2026-11-05", "x", UUID.randomUUID()))
				.getResponse().getStatus()).isEqualTo(404);
		assertThat(count(s.fx().workspaceId())).isZero();
		assertThat(count(other.workspaceId())).isZero();
	}

	@Test
	void aMissingTypeOrAccountIsRejectedWithTheFieldName() throws Exception {
		Setup s = setup();

		assertInvalid(s.fx(), "{\"accountId\":\"%s\",\"amountCents\":100,\"occurredOn\":\"2026-11-05\",\"description\":\"x\"}"
				.formatted(s.account()), "type");
		assertInvalid(s.fx(), "{\"type\":\"EXPENSE\",\"amountCents\":100,\"occurredOn\":\"2026-11-05\",\"description\":\"x\"}",
				"accountId");
		assertThat(count(s.fx().workspaceId())).isZero();
	}

}
