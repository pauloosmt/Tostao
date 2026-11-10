package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.transaction.PendingScenario.account;
import static com.tostao.tostao_api.transaction.PendingScenario.body;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static com.tostao.tostao_api.transaction.PendingScenario.create;
import static com.tostao.tostao_api.transaction.PendingScenario.transferBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** História 2: efetivar um lançamento pendente. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ClearTransactionTest {

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

	private UUID pendingExpense(PendingScenario s) throws Exception {
		return idOf(create(mvc, s.owner(), body("EXPENSE", s.checking(), 80_000, "2026-11-20", "Aluguel", s.food(),
				"PENDING")).andExpect(status().isCreated()));
	}

	/** Todas as colunas da linha, para provar que nada mudou. */
	private Map<String, Object> row(UUID id) {
		return owner.client().sql("select * from transactions where id = :id").param("id", id).query().singleRow();
	}

	@Test
	void clearingAPendingExpenseMakesItCountInTheBalance() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingExpense(s);
		account(mvc, s.owner(), s.checking()).andExpect(jsonPath("$.balanceCents").value(95_000));

		clear(mvc, s.owner(), id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLEARED"))
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(header().string("Cache-Control", containsString("no-store")));

		account(mvc, s.owner(), s.checking()).andExpect(jsonPath("$.balanceCents").value(15_000))
				.andExpect(jsonPath("$.pendingCents").value(0)).andExpect(jsonPath("$.projectedBalanceCents").value(15_000));
	}

	@Test
	void clearingOnlyChangesTheStatusAndTheUpdateDate() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingExpense(s);
		Map<String, Object> before = row(id);

		clear(mvc, s.owner(), id).andExpect(status().isOk());

		Map<String, Object> after = row(id);
		for (String column : before.keySet()) {
			if (column.equals("status") || column.equals("updated_at")) {
				continue;
			}
			assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
		}
		assertThat(after.get("status")).isEqualTo("CLEARED");
		assertThat(((java.sql.Timestamp) after.get("updated_at")).toInstant())
				.isAfter(((java.sql.Timestamp) before.get("updated_at")).toInstant());
	}

	@Test
	void clearingATransferClearsBothLegsTogether() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID leg = idOf(create(mvc, s.owner(), transferBody(s.checking(), s.savings(), 10_000, "2026-11-28", "Reserva",
				"PENDING")).andExpect(status().isCreated()));
		UUID group = owner.client().sql("select transfer_group_id from transactions where id = :id").param("id", leg)
				.query(UUID.class).single();

		clear(mvc, s.owner(), leg).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLEARED"));

		List<String> statuses = owner.client().sql("select status from transactions where transfer_group_id = :g")
				.param("g", group).query(String.class).list();
		assertThat(statuses).containsExactly("CLEARED", "CLEARED");
		account(mvc, s.owner(), s.checking()).andExpect(jsonPath("$.balanceCents").value(85_000));
		account(mvc, s.owner(), s.savings()).andExpect(jsonPath("$.balanceCents").value(10_000));
	}

	@Test
	void clearingTheOtherLegOfATransferWorksTheSame() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		create(mvc, s.owner(), transferBody(s.checking(), s.savings(), 10_000, "2026-11-28", "Reserva", "PENDING"))
				.andExpect(status().isCreated());
		UUID inflow = owner.client().sql("select id from transactions where account_id = :a and status = 'PENDING'")
				.param("a", s.savings()).query(UUID.class).single();

		clear(mvc, s.owner(), inflow).andExpect(status().isOk());

		assertThat(owner.client().sql("select count(*) from transactions where status = 'PENDING' and workspace_id = :w")
				.param("w", s.owner().workspaceId()).query(Long.class).single()).isZero();
	}

	@Test
	void clearingAClearedTransactionIsASuccessWithoutAnyEffect() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingExpense(s);
		clear(mvc, s.owner(), id).andExpect(status().isOk());
		Map<String, Object> afterFirst = row(id);

		clear(mvc, s.owner(), id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLEARED"));

		// nenhuma coluna muda, nem a data de última alteração (SC-006)
		assertThat(row(id)).isEqualTo(afterFirst);
		// e o que já nasceu efetivado também não muda
		UUID cleared = owner.client().sql("select id from transactions where workspace_id = :w and description = 'Efetivada'")
				.param("w", s.owner().workspaceId()).query(UUID.class).single();
		Map<String, Object> clearedBefore = row(cleared);
		clear(mvc, s.owner(), cleared).andExpect(status().isOk());
		assertThat(row(cleared)).isEqualTo(clearedBefore);
	}

	@Test
	void clearingWorksOnAnArchivedAccountAndCategory() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingExpense(s);
		owner.client().sql("update accounts set archived = true where id = :id").param("id", s.checking()).update();
		owner.client().sql("update categories set archived = true where id = :id").param("id", s.food()).update();

		clear(mvc, s.owner(), id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLEARED"));
	}

	@Test
	void aDeletedUnknownOtherWorkspaceOrMalformedIdIsNotFound() throws Exception {
		PendingScenario a = PendingScenario.create(fixtures());
		PendingScenario b = PendingScenario.create(fixtures());
		UUID deleted = pendingExpense(a);
		owner.client().sql("update transactions set deleted_at = now() where id = :id").param("id", deleted).update();
		UUID foreign = idOf(create(mvc, b.owner(), body("EXPENSE", b.checking(), 100, "2026-11-20", "De B", b.food(),
				"PENDING")).andExpect(status().isCreated()));

		clear(mvc, a.owner(), deleted).andExpect(status().isNotFound());
		clear(mvc, a.owner(), UUID.randomUUID()).andExpect(status().isNotFound());
		clear(mvc, a.owner(), foreign).andExpect(status().isNotFound());
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.post(PendingScenario.transactionsUrl(a.owner().workspaceId()) + "/abc/clear")
				.cookie(com.tostao.tostao_api.support.AuthSupport.session(a.owner().token())))
				.andExpect(status().isNotFound());

		assertThat(PendingScenario.statusOf(owner, foreign)).isEqualTo("PENDING");
	}

	@Test
	void theCreatorNeverChangesWhenAnotherPersonClears() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		Fixture editor = fixtures().addMember(s.owner().workspaceId(), "EDITOR");
		UUID id = pendingExpense(s);

		clear(mvc, editor, id).andExpect(status().isOk())
				.andExpect(jsonPath("$.createdBy").value(s.owner().userId().toString()));
	}

	@Test
	void theResponseAfterClearingEqualsTheOneOfAGet() throws Exception {
		PendingScenario s = PendingScenario.create(fixtures());
		UUID id = pendingExpense(s);

		String cleared = clear(mvc, s.owner(), id).andReturn().getResponse().getContentAsString();
		Instant updated = Instant.parse(JsonPath.read(cleared, "$.updatedAt"));

		assertThat(updated).isNotNull();
		assertThat((String) JsonPath.read(cleared, "$.status")).isEqualTo("CLEARED");
	}

}
