package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
class TransferTest {

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

	private static String transfer(UUID from, UUID to, long cents) {
		return "{\"type\":\"TRANSFER\",\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"2026-11-06\",\"description\":\"Reserva\",\"notes\":\"mensal\"}"
				.formatted(from, to, cents);
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

	private long total(Fixture fx) throws Exception {
		String json = mvc.perform(get("/api/v1/workspaces/" + fx.workspaceId() + "/reports/balance")
				.cookie(session(fx.token()))).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(json, "$.totalBalanceCents")).longValue();
	}

	private void assertInvalid(Fixture fx, String json, String field) throws Exception {
		MvcResult result = create(fx, json);
		assertThat(result.getResponse().getStatus()).as(json).isEqualTo(400);
		assertThat(result.getResponse().getContentAsString()).as(json).contains("\"field\":\"" + field + "\"");
	}

	private record Setup(Fixture fx, UUID checking, UUID savings) {
	}

	private Setup setup() {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID checking = fixtures().insertAccount(fx.workspaceId(), "Corrente", "CHECKING", 100_000, false);
		UUID savings = fixtures().insertAccount(fx.workspaceId(), "Poupanca", "SAVINGS", 0, false);
		return new Setup(fx, checking, savings);
	}

	@Test
	void aTransferCreatesTwoLinkedLegsAndKeepsTheTotal() throws Exception {
		Setup s = setup();
		long totalBefore = total(s.fx());

		MvcResult result = create(s.fx(), transfer(s.checking(), s.savings(), 30_000));

		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		String json = result.getResponse().getContentAsString();
		assertThat(((Number) JsonPath.read(json, "$.amountCents")).longValue()).isEqualTo(-30_000);
		assertThat((String) JsonPath.read(json, "$.type")).isEqualTo("TRANSFER");
		assertThat((String) JsonPath.read(json, "$.accountId")).isEqualTo(s.checking().toString());
		assertThat((String) JsonPath.read(json, "$.counterpartAccountId")).isEqualTo(s.savings().toString());
		assertThat((Object) JsonPath.read(json, "$.categoryId")).isNull();
		assertThat((String) JsonPath.read(json, "$.transferGroupId")).isNotBlank();
		assertThat(result.getResponse().getHeader("Location")).endsWith("/" + JsonPath.read(json, "$.id"));

		List<Map<String, Object>> legs = owner.client().sql("""
				select account_id, amount_cents, occurred_on, description, notes, created_by, category_id, type
				from transactions where workspace_id = :w and transfer_group_id = :g order by amount_cents""")
				.param("w", s.fx().workspaceId())
				.param("g", UUID.fromString(JsonPath.read(json, "$.transferGroupId")))
				.query().listOfRows();
		assertThat(legs).hasSize(2);
		assertThat(legs.get(0).get("account_id")).isEqualTo(s.checking());
		assertThat(((Number) legs.get(0).get("amount_cents")).longValue()).isEqualTo(-30_000);
		assertThat(legs.get(1).get("account_id")).isEqualTo(s.savings());
		assertThat(((Number) legs.get(1).get("amount_cents")).longValue()).isEqualTo(30_000);
		for (Map<String, Object> leg : legs) {
			assertThat(leg.get("occurred_on").toString()).isEqualTo("2026-11-06");
			assertThat(leg.get("description")).isEqualTo("Reserva");
			assertThat(leg.get("notes")).isEqualTo("mensal");
			assertThat(leg.get("created_by")).isEqualTo(s.fx().userId());
			assertThat(leg.get("category_id")).isNull();
			assertThat(leg.get("type")).isEqualTo("TRANSFER");
		}
		assertThat(balance(s.fx(), s.checking())).isEqualTo(70_000);
		assertThat(balance(s.fx(), s.savings())).isEqualTo(30_000);
		assertThat(total(s.fx())).isEqualTo(totalBefore);
	}

	@Test
	void aTransferToOrFromACreditCardIsAccepted() throws Exception {
		Setup s = setup();
		UUID card = fixtures().insertAccount(s.fx().workspaceId(), "Cartao", "CREDIT_CARD", -50_000, false);

		assertThat(create(s.fx(), transfer(s.checking(), card, 50_000)).getResponse().getStatus()).isEqualTo(201);
		assertThat(balance(s.fx(), card)).isZero();
		assertThat(balance(s.fx(), s.checking())).isEqualTo(50_000);
		assertThat(create(s.fx(), transfer(card, s.savings(), 1_000)).getResponse().getStatus()).isEqualTo(201);
	}

	@Test
	void invalidTransfersAreRejectedAndCreateNothing() throws Exception {
		Setup s = setup();
		String valid = transfer(s.checking(), s.savings(), 1_000);

		assertInvalid(s.fx(), valid.replace(",\"destinationAccountId\":\"" + s.savings() + "\"", ""),
				"destinationAccountId");
		assertInvalid(s.fx(), transfer(s.checking(), s.checking(), 1_000), "destinationAccountId");
		assertInvalid(s.fx(), valid.substring(0, valid.length() - 1) + ",\"categoryId\":\"" + UUID.randomUUID() + "\"}",
				"categoryId");
		for (long cents : new long[] { 0, -1, 1_000_000_000_001L }) {
			assertInvalid(s.fx(), transfer(s.checking(), s.savings(), cents), "amountCents");
		}
		assertThat(create(s.fx(), valid.replace("1000", "10.5")).getResponse().getStatus()).isEqualTo(400);
		assertThat(count(s.fx().workspaceId())).isZero();
	}

	@Test
	void anArchivedEndpointRefusesTheTransferWithoutCreatingEitherLeg() throws Exception {
		Setup s = setup();
		UUID archived = fixtures().insertAccount(s.fx().workspaceId(), "Velha", "CHECKING", 0, true);

		assertThat(create(s.fx(), transfer(s.checking(), archived, 1_000)).getResponse().getStatus()).isEqualTo(409);
		assertThat(create(s.fx(), transfer(archived, s.savings(), 1_000)).getResponse().getStatus()).isEqualTo(409);
		assertThat(count(s.fx().workspaceId())).isZero();
	}

	@Test
	void anEndpointOfAnotherWorkspaceOrMissingIsNotFoundAndCreatesNothing() throws Exception {
		Setup s = setup();
		Fixture other = fixtures().workspaceWithOwner();
		UUID foreign = fixtures().insertAccount(other.workspaceId(), "Alheia", "CHECKING", 0, false);

		assertThat(create(s.fx(), transfer(s.checking(), foreign, 1_000)).getResponse().getStatus()).isEqualTo(404);
		assertThat(create(s.fx(), transfer(foreign, s.savings(), 1_000)).getResponse().getStatus()).isEqualTo(404);
		assertThat(create(s.fx(), transfer(s.checking(), UUID.randomUUID(), 1_000)).getResponse().getStatus())
				.isEqualTo(404);
		assertThat(count(s.fx().workspaceId())).isZero();
		assertThat(count(other.workspaceId())).isZero();
	}

	@Test
	void aFailureWritingTheSecondLegLeavesNeitherLegBehind() throws Exception {
		Setup s = setup();
		owner.client().sql("""
				create function fail_second_leg() returns trigger language plpgsql as
				$$ begin raise exception 'segunda ponta forçada a falhar'; end $$""").update();
		owner.client().sql("""
				create trigger fail_second_leg before insert on transactions
				for each row when (new.amount_cents > 0) execute function fail_second_leg()""").update();
		try {
			try {
				MvcResult result = create(s.fx(), transfer(s.checking(), s.savings(), 1_000));
				assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(500);
				assertThat(result.getResponse().getContentAsString()).doesNotContain("forçada");
			}
			catch (jakarta.servlet.ServletException e) {
				// o MockMvc devolve a exceção não tratada em vez de montar a resposta 500
				assertThat(e).hasStackTraceContaining("segunda ponta forçada a falhar");
			}

			assertThat(count(s.fx().workspaceId())).isZero();
		}
		finally {
			owner.client().sql("drop trigger fail_second_leg on transactions").update();
			owner.client().sql("drop function fail_second_leg()").update();
		}
	}

}
