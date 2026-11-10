package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.transaction.PendingScenario.account;
import static com.tostao.tostao_api.transaction.PendingScenario.body;
import static com.tostao.tostao_api.transaction.PendingScenario.create;
import static com.tostao.tostao_api.transaction.PendingScenario.edit;
import static com.tostao.tostao_api.transaction.PendingScenario.transferBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

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
import com.tostao.tostao_api.support.AuthSupport;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/** História 5: mudar o estado pela edição (efetivar com data nova, voltar a pendente, sem estado mantém). */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class UpdateStatusTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private static UUID idOf(ResultActions result) throws Exception {
		return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id"));
	}

	/** Corpo da edição de receita ou despesa; {@code status} nulo não aparece no corpo. */
	private static String editBody(UUID account, long cents, String date, String description, UUID category, String status) {
		String s = "{\"accountId\":\"%s\",\"amountCents\":%d,\"occurredOn\":\"%s\",\"description\":\"%s\",\"categoryId\":\"%s\""
				.formatted(account, cents, date, description, category);
		return s + (status == null ? "" : ",\"status\":\"" + status + "\"") + "}";
	}

	private UUID pendingRent(PendingScenario s) throws Exception {
		return idOf(create(mvc, s.owner(), body("EXPENSE", s.checking(), 80_000, "2026-11-20", "Aluguel", s.food(), "PENDING"))
				.andExpect(status().isCreated()));
	}

	private long balance(PendingScenario s, UUID accountId) throws Exception {
		String json = account(mvc, s.owner(), accountId).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(json, "$.balanceCents")).longValue();
	}

	private long monthlySpent(PendingScenario s) throws Exception {
		String json = mvc.perform(get("/api/v1/workspaces/" + s.owner().workspaceId() + "/monthly-budgets?month=2026-11")
				.cookie(AuthSupport.session(s.owner().token()))).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(json, "$.totalSpentCents")).longValue();
	}

	@Test
	void anEditThatInformsTheStatusClearsOrReturnsToPendingTogetherWithTheOtherChanges() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingRent(s);

		edit(mvc, s.owner(), id, editBody(s.checking(), 70_000, "2026-11-21", "Aluguel pago", s.food(), "CLEARED"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLEARED"))
				.andExpect(jsonPath("$.amountCents").value(-70_000)).andExpect(jsonPath("$.occurredOn").value("2026-11-21"))
				.andExpect(jsonPath("$.description").value("Aluguel pago"));
		org.assertj.core.api.Assertions.assertThat(balance(s, s.checking())).isEqualTo(25_000);

		edit(mvc, s.owner(), id, editBody(s.checking(), 70_000, "2026-11-21", "Aluguel pago", s.food(), "PENDING"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
		org.assertj.core.api.Assertions.assertThat(balance(s, s.checking())).isEqualTo(95_000);
	}

	@Test
	void withoutAStatusTheCurrentOneIsKept() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID pending = pendingRent(s);
		UUID cleared = owner.client().sql("select id from transactions where workspace_id = :w and description = 'Efetivada'")
				.param("w", s.owner().workspaceId()).query(UUID.class).single();

		edit(mvc, s.owner(), pending, editBody(s.checking(), 80_000, "2026-11-20", "Aluguel de novembro", s.food(), null))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.description").value("Aluguel de novembro"));
		edit(mvc, s.owner(), cleared, editBody(s.checking(), 5_000, "2026-11-02", "Mercado", s.food(), null))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLEARED"));
		org.assertj.core.api.Assertions.assertThat(balance(s, s.checking())).isEqualTo(95_000);
	}

	@Test
	void aTransferChangesBothLegsTogetherByEditing() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID leg = idOf(create(mvc, s.owner(), transferBody(s.checking(), s.savings(), 10_000, "2026-11-28", "Reserva",
				"PENDING")).andExpect(status().isCreated()));
		String transfer = "{\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":10000,\"occurredOn\":\"2026-11-28\",\"description\":\"Reserva\",\"status\":\"%s\"}";

		edit(mvc, s.owner(), leg, transfer.formatted(s.checking(), s.savings(), "CLEARED")).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CLEARED"));
		assertThat(owner.client().sql("select status from transactions where account_id in (:a, :b) and type = 'TRANSFER'")
				.param("a", s.checking()).param("b", s.savings()).query(String.class).list()).containsExactly("CLEARED", "CLEARED");
		assertThat(balance(s, s.savings())).isEqualTo(10_000);

		edit(mvc, s.owner(), leg, transfer.formatted(s.checking(), s.savings(), "PENDING")).andExpect(status().isOk());
		List<String> statuses = owner.client().sql("select status from transactions where type = 'TRANSFER' and workspace_id = :w")
				.param("w", s.owner().workspaceId()).query(String.class).list();
		assertThat(statuses).containsExactly("PENDING", "PENDING");
		assertThat(balance(s, s.savings())).isZero();
	}

	@Test
	void goingToPendingAndBackLeavesEveryRealizedNumberExactlyAsItWas() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		fixtures().insertMonthlyBudget(w, java.time.LocalDate.of(2026, 11, 1), s.food(), 100_000, s.owner().userId());
		UUID id = idOf(create(mvc, s.owner(), body("EXPENSE", s.checking(), 80_000, "2026-11-20", "Aluguel", s.food(), "CLEARED"))
				.andExpect(status().isCreated()));
		long balanceCleared = balance(s, s.checking());
		long spentCleared = monthlySpent(s);

		edit(mvc, s.owner(), id, editBody(s.checking(), 80_000, "2026-11-20", "Aluguel", s.food(), "PENDING")).andExpect(status().isOk());
		assertThat(balance(s, s.checking())).isEqualTo(balanceCleared + 80_000);
		assertThat(monthlySpent(s)).isEqualTo(spentCleared - 80_000);

		edit(mvc, s.owner(), id, editBody(s.checking(), 80_000, "2026-11-20", "Aluguel", s.food(), "CLEARED")).andExpect(status().isOk());
		assertThat(balance(s, s.checking())).isEqualTo(balanceCleared);
		assertThat(monthlySpent(s)).isEqualTo(spentCleared);
	}

	@Test
	void anInvalidStatusIsRejectedNamingTheFieldAndNothingChanges() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingRent(s);
		String before = PendingScenario.transactionsFingerprint(owner, s.owner().workspaceId());

		for (String bad : new String[] { "\"OUTRO\"", "\"cleared\"", "\"\"", "1", "true" }) {
			String json = editBody(s.checking(), 1, "2026-11-20", "x", s.food(), null);
			json = json.substring(0, json.length() - 1) + ",\"status\":" + bad + "}";
			edit(mvc, s.owner(), id, json).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("status"));
		}
		assertThat(PendingScenario.transactionsFingerprint(owner, s.owner().workspaceId())).isEqualTo(before);
	}

}
