package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.transaction.PendingScenario.account;
import static com.tostao.tostao_api.transaction.PendingScenario.body;
import static com.tostao.tostao_api.transaction.PendingScenario.create;
import static com.tostao.tostao_api.transaction.PendingScenario.list;
import static com.tostao.tostao_api.transaction.PendingScenario.transferBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** História 1: registrar um lançamento previsto sem mexer no realizado. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PendingTransactionTest {

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

	private long balance(PendingScenario s, UUID accountId) throws Exception {
		String json = account(mvc, s.owner(), accountId).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(json, "$.balanceCents")).longValue();
	}

	@Test
	void withoutAStatusTheTransactionIsClearedLikeBefore() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());

		create(mvc, s.owner(), body("EXPENSE", s.checking(), 1_000, "2026-11-10", "Sem estado", s.food(), null))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CLEARED"));
		create(mvc, s.owner(), body("EXPENSE", s.checking(), 1_000, "2026-11-10", "Explícito", s.food(), "CLEARED"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("CLEARED"));
		// 100000 - 5000 (do cenário) - 1000 - 1000
		assertThat(balance(s, s.checking())).isEqualTo(93_000);
	}

	@Test
	void aPendingExpenseIncomeAndTransferAreCreatedPendingAndDoNotMoveTheBalance() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());

		create(mvc, s.owner(), body("EXPENSE", s.checking(), 80_000, "2026-11-20", "Aluguel", s.food(), "PENDING"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
		create(mvc, s.owner(), body("INCOME", s.checking(), 30_000, "2026-11-25", "Adiantamento", s.salary(), "PENDING"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"));
		UUID transfer = idOf(create(mvc, s.owner(), transferBody(s.checking(), s.savings(), 10_000, "2026-11-28",
				"Reserva", "PENDING")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")));

		assertThat(balance(s, s.checking())).as("saldo da Corrente só com o efetivado").isEqualTo(95_000);
		assertThat(balance(s, s.savings())).isZero();
		// a transferência tem duas pontas, e as duas ficam pendentes
		assertThat(owner.client().sql("select count(*) from transactions where transfer_group_id = "
				+ "(select transfer_group_id from transactions where id = :id) and status = 'PENDING'")
				.param("id", transfer).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void theListShowsTheStatusOfEachTransaction() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		create(mvc, s.owner(), body("EXPENSE", s.checking(), 80_000, "2026-11-20", "Aluguel", s.food(), "PENDING"))
				.andExpect(status().isCreated());

		list(mvc, s.owner(), "").andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2))
				.andExpect(jsonPath("$.items[?(@.description=='Aluguel')].status").value("PENDING"))
				.andExpect(jsonPath("$.items[?(@.description=='Efetivada')].status").value("CLEARED"));
	}

	@Test
	void anUnknownStatusIsRejectedNamingTheFieldAndNothingIsCreated() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		long before = owner.client().sql("select count(*) from transactions where workspace_id = :w")
				.param("w", w).query(Long.class).single();

		for (String bad : new String[] { "\"OUTRO\"", "\"pending\"", "\"\"", "1", "true" }) {
			String json = body("EXPENSE", s.checking(), 100, "2026-11-10", "x", s.food(), null);
			json = json.substring(0, json.length() - 1) + ",\"status\":" + bad + "}";
			create(mvc, s.owner(), json).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("status"));
		}
		assertThat(owner.client().sql("select count(*) from transactions where workspace_id = :w")
				.param("w", w).query(Long.class).single()).isEqualTo(before);
	}

	@Test
	void aPendingTransactionAcceptsPastAndFutureDatesAndStaysPending() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());

		UUID past = idOf(create(mvc, s.owner(), body("EXPENSE", s.checking(), 100, "2020-01-15", "Atrasado", s.food(),
				"PENDING")).andExpect(status().isCreated()));
		UUID future = idOf(create(mvc, s.owner(), body("INCOME", s.checking(), 100, "2099-12-31", "Futuro", s.salary(),
				"PENDING")).andExpect(status().isCreated()));

		// o sistema nunca efetiva sozinho por causa da data (FR-010)
		assertThat(PendingScenario.statusOf(owner, past)).isEqualTo("PENDING");
		assertThat(PendingScenario.statusOf(owner, future)).isEqualTo("PENDING");
	}

	@Test
	void fieldsOutsideTheAllowedListAreStillRejected() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());

		for (String extra : new String[] { "\"workspaceId\":\"" + UUID.randomUUID() + "\"",
				"\"createdBy\":\"" + UUID.randomUUID() + "\"", "\"transferGroupId\":\"" + UUID.randomUUID() + "\"",
				"\"deletedAt\":\"2020-01-01T00:00:00Z\"", "\"createdAt\":\"2020-01-01T00:00:00Z\"" }) {
			String json = body("EXPENSE", s.checking(), 100, "2026-11-10", "x", s.food(), "PENDING");
			json = json.substring(0, json.length() - 1) + "," + extra + "}";
			create(mvc, s.owner(), json).andExpect(status().isBadRequest());
		}
	}

	@Test
	void aDeletedPendingTransactionCountsNowhere() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = idOf(create(mvc, s.owner(), body("EXPENSE", s.checking(), 80_000, "2026-11-20", "Aluguel", s.food(),
				"PENDING")).andExpect(status().isCreated()));

		mvc.perform(delete(PendingScenario.transactionsUrl(s.owner().workspaceId()) + "/" + id)
				.cookie(AuthSupport.session(s.owner().token()))).andExpect(status().isNoContent());

		list(mvc, s.owner(), "status=PENDING").andExpect(jsonPath("$.totalItems").value(0));
		account(mvc, s.owner(), s.checking()).andExpect(jsonPath("$.pendingCents").value(0))
				.andExpect(jsonPath("$.projectedBalanceCents").value(95_000));
	}

}
