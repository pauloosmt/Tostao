package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * SC-001 e SC-002: depois de qualquer sequência de criações, edições e exclusões, o saldo de cada conta é o
 * saldo inicial mais a soma dos lançamentos listados da conta, o saldo total é a soma dos saldos e as
 * transferências somam zero. Sequência pseudoaleatória com semente fixa (reprodutível).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class BalanceConsistencyTest {

	private static final int OPERATIONS = 200;

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private Fixture fx;
	private final Random random = new Random(20261106L);
	private final List<UUID> accounts = new ArrayList<>();
	private final Map<UUID, Long> initial = new HashMap<>();
	private UUID expenseCategory;
	private UUID incomeCategory;

	private String base() {
		return "/api/v1/workspaces/" + fx.workspaceId();
	}

	private String json(String path, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			String body) throws Exception {
		var builder = request.cookie(session(fx.token()));
		if (body != null) {
			builder = builder.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		var response = mvc.perform(builder).andReturn().getResponse();
		assertThat(response.getStatus()).as(path + " " + body).isLessThan(300);
		return response.getContentAsString();
	}

	private UUID pick(List<UUID> from) {
		return from.get(random.nextInt(from.size()));
	}

	/** Todos os lançamentos vigentes, percorrendo todas as páginas. */
	private List<Map<String, Object>> allTransactions() throws Exception {
		List<Map<String, Object>> all = new ArrayList<>();
		for (int page = 1;; page++) {
			String body = json("list", get(base() + "/transactions?size=100&page=" + page), null);
			List<Map<String, Object>> items = JsonPath.read(body, "$.items");
			all.addAll(items);
			if (page >= (Integer) JsonPath.read(body, "$.totalPages")) {
				return all;
			}
		}
	}

	private long cents(Object value) {
		return ((Number) value).longValue();
	}

	@Test
	void balancesStayExactAfterTwoHundredRandomOperations() throws Exception {
		fx = new WorkspaceFixtures(owner, tokens).workspaceWithOwner();
		WorkspaceFixtures fixtures = new WorkspaceFixtures(owner, tokens);
		long[] opening = { 100_000, 0, -25_050 };
		String[] types = { "CHECKING", "SAVINGS", "CREDIT_CARD" };
		for (int i = 0; i < 3; i++) {
			UUID id = fixtures.insertAccount(fx.workspaceId(), "Conta " + i, types[i], opening[i], false);
			accounts.add(id);
			initial.put(id, opening[i]);
		}
		expenseCategory = fixtures.insertCategory(fx.workspaceId(), null, "Despesas", "EXPENSE", false);
		incomeCategory = fixtures.insertCategory(fx.workspaceId(), null, "Receitas", "INCOME", false);

		for (int step = 1; step <= OPERATIONS; step++) {
			List<Map<String, Object>> live = allTransactions();
			int dice = random.nextInt(10);
			if (live.isEmpty() || dice < 3) {
				createLedger();
			}
			else if (dice < 5) {
				createTransfer();
			}
			else if (dice < 8) {
				editOne(live);
			}
			else {
				deleteOne(live);
			}
			assertConsistent(step);
		}
	}

	private void createLedger() throws Exception {
		boolean income = random.nextBoolean();
		String body = "{\"type\":\"%s\",\"accountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"2026-%02d-%02d\",\"description\":\"Aleatorio\",\"categoryId\":\"%s\"}"
				.formatted(income ? "INCOME" : "EXPENSE", pick(accounts), 1 + random.nextInt(50_000),
						1 + random.nextInt(12), 1 + random.nextInt(28), income ? incomeCategory : expenseCategory);
		json("create", post(base() + "/transactions"), body);
	}

	private void createTransfer() throws Exception {
		UUID from = pick(accounts);
		UUID to;
		do {
			to = pick(accounts);
		}
		while (to.equals(from));
		String body = "{\"type\":\"TRANSFER\",\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"2026-%02d-%02d\",\"description\":\"Mover\"}"
				.formatted(from, to, 1 + random.nextInt(30_000), 1 + random.nextInt(12), 1 + random.nextInt(28));
		json("transfer", post(base() + "/transactions"), body);
	}

	private void editOne(List<Map<String, Object>> live) throws Exception {
		Map<String, Object> item = live.get(random.nextInt(live.size()));
		String id = (String) item.get("id");
		long cents = 1 + random.nextInt(50_000);
		String body;
		if ("TRANSFER".equals(item.get("type"))) {
			UUID from = pick(accounts);
			UUID to;
			do {
				to = pick(accounts);
			}
			while (to.equals(from));
			body = "{\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"2026-05-05\",\"description\":\"Mover editada\"}"
					.formatted(from, to, cents);
		}
		else {
			body = "{\"accountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"2026-05-05\",\"description\":\"Editada\",\"categoryId\":\"%s\"}"
					.formatted(pick(accounts), cents, item.get("categoryId"));
		}
		json("edit", put(base() + "/transactions/" + id), body);
	}

	private void deleteOne(List<Map<String, Object>> live) throws Exception {
		Map<String, Object> item = live.get(random.nextInt(live.size()));
		json("delete", delete(base() + "/transactions/" + item.get("id")), null);
	}

	private void assertConsistent(int step) throws Exception {
		List<Map<String, Object>> live = allTransactions();
		Map<UUID, Long> sums = new HashMap<>();
		long transfers = 0;
		for (Map<String, Object> item : live) {
			sums.merge(UUID.fromString((String) item.get("accountId")), cents(item.get("amountCents")), Long::sum);
			if ("TRANSFER".equals(item.get("type"))) {
				transfers += cents(item.get("amountCents"));
			}
		}
		String balances = json("accounts", get(base() + "/accounts"), null);
		long sumOfBalances = 0;
		for (UUID account : accounts) {
			long expected = initial.get(account) + sums.getOrDefault(account, 0L);
			List<Number> reported = JsonPath.read(balances, "$[?(@.id == '" + account + "')].balanceCents");
			assertThat(reported).as("passo %d, conta %s".formatted(step, account)).hasSize(1);
			assertThat(reported.get(0).longValue()).as("passo %d, conta %s".formatted(step, account))
					.isEqualTo(expected);
			sumOfBalances += expected;
		}
		String report = json("report", get(base() + "/reports/balance"), null);
		assertThat(cents(JsonPath.read(report, "$.totalBalanceCents"))).as("passo " + step).isEqualTo(sumOfBalances);
		assertThat(transfers).as("transferências somam zero, passo " + step).isZero();
		long totalFromDb = owner.client().sql("""
				select coalesce(sum(amount_cents), 0) from transactions
				where workspace_id = :w and deleted_at is null and type = 'TRANSFER'""")
				.param("w", fx.workspaceId()).query(Long.class).single();
		assertThat(totalFromDb).isZero();
	}

}
