package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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
class DeleteTransactionTest {

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

	private MvcResult remove(Fixture who, UUID workspaceId, UUID id) throws Exception {
		return mvc.perform(delete(base(workspaceId) + "/" + id).cookie(session(who.token()))).andReturn();
	}

	private long balance(World s, UUID account) throws Exception {
		String json = mvc.perform(get("/api/v1/workspaces/" + s.fx().workspaceId() + "/accounts/" + account)
				.cookie(session(s.fx().token()))).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(json, "$.balanceCents")).longValue();
	}

	private Map<String, Object> row(UUID id) {
		return owner.client().sql("""
				select account_id, category_id, amount_cents, occurred_on, description, notes, created_by,
				       updated_at, deleted_at, transfer_group_id
				from transactions where id = :id""").param("id", id).query().singleRow();
	}

	private record World(Fixture fx, UUID a, UUID b, UUID food, UUID expense, UUID out, UUID in) {
	}

	/** Contas A (100.000) e B (0), uma despesa de 10.000 em A e uma transferência A→B de 3.000. */
	private World world() {
		Fixture fx = fixtures().workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID a = fixtures().insertAccount(w, "A", "CHECKING", 100_000, false);
		UUID b = fixtures().insertAccount(w, "B", "SAVINGS", 0, false);
		UUID food = fixtures().insertCategory(w, null, "Comida", "EXPENSE", false);
		UUID expense = fixtures().insertLedgerTransaction(w, a, fx.userId(), food, "EXPENSE", 10_000, DAY, "Mercado",
				false);
		UUID[] pair = fixtures().insertTransferPair(w, a, b, fx.userId(), 3_000, DAY, "Reserva");
		return new World(fx, a, b, food, expense, pair[0], pair[1]);
	}

	@Test
	void deletingAnExpenseHidesItRestoresTheBalanceAndKeepsTheRow() throws Exception {
		World s = world();
		Map<String, Object> before = row(s.expense());
		assertThat(balance(s, s.a())).isEqualTo(87_000);

		MvcResult result = remove(s.fx(), s.fx().workspaceId(), s.expense());

		assertThat(result.getResponse().getStatus()).isEqualTo(204);
		assertThat(result.getResponse().getContentAsString()).isEmpty();
		assertThat(balance(s, s.a())).isEqualTo(97_000);
		mvc.perform(get(base(s.fx().workspaceId())).cookie(session(s.fx().token())))
				.andExpect(jsonPath("$.totalItems").value(2)).andExpect(jsonPath("$.items", hasSize(2)));
		mvc.perform(get(base(s.fx().workspaceId()) + "/" + s.expense()).cookie(session(s.fx().token())))
				.andExpect(status().isNotFound());

		// continua guardada, com a data da exclusão e o resto intacto
		Map<String, Object> after = row(s.expense());
		assertThat(after.get("deleted_at")).isNotNull();
		assertThat(after.get("updated_at")).isNotNull();
		for (String column : new String[] { "account_id", "category_id", "amount_cents", "occurred_on", "description",
				"notes", "created_by" }) {
			assertThat(after.get(column)).as(column).isEqualTo(before.get(column));
		}
	}

	@Test
	void deletingEitherLegOfATransferDeletesBothAndRestoresBothBalances() throws Exception {
		World viaOut = world();
		World viaIn = world();

		assertThat(remove(viaOut.fx(), viaOut.fx().workspaceId(), viaOut.out()).getResponse().getStatus())
				.isEqualTo(204);
		assertThat(remove(viaIn.fx(), viaIn.fx().workspaceId(), viaIn.in()).getResponse().getStatus())
				.isEqualTo(204);

		for (World s : new World[] { viaOut, viaIn }) {
			Map<String, Object> out = row(s.out());
			Map<String, Object> in = row(s.in());
			assertThat(out.get("deleted_at")).isNotNull().isEqualTo(in.get("deleted_at"));
			assertThat(balance(s, s.a())).isEqualTo(90_000);
			assertThat(balance(s, s.b())).isZero();
			mvc.perform(get(base(s.fx().workspaceId()) + "/" + s.out()).cookie(session(s.fx().token())))
					.andExpect(status().isNotFound());
			mvc.perform(get(base(s.fx().workspaceId()) + "/" + s.in()).cookie(session(s.fx().token())))
					.andExpect(status().isNotFound());
			mvc.perform(get(base(s.fx().workspaceId())).cookie(session(s.fx().token())))
					.andExpect(jsonPath("$.totalItems").value(1));
		}
	}

	@Test
	void aDeletedTransactionCannotBeDeletedOrEditedAgain() throws Exception {
		World s = world();
		remove(s.fx(), s.fx().workspaceId(), s.expense());
		remove(s.fx(), s.fx().workspaceId(), s.out());
		String body = "{\"accountId\":\"%s\",\"amountCents\":100,\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"categoryId\":\"%s\"}"
				.formatted(s.a(), s.food());

		assertThat(remove(s.fx(), s.fx().workspaceId(), s.expense()).getResponse().getStatus()).isEqualTo(404);
		assertThat(remove(s.fx(), s.fx().workspaceId(), s.in()).getResponse().getStatus()).isEqualTo(404);
		mvc.perform(put(base(s.fx().workspaceId()) + "/" + s.expense()).cookie(session(s.fx().token()))
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
	}

	@Test
	void deletingWorksOnArchivedAccountsAndCategories() throws Exception {
		World s = world();
		UUID w = s.fx().workspaceId();
		UUID archivedAccount = fixtures().insertAccount(w, "Velha", "CHECKING", 0, true);
		UUID archivedCategory = fixtures().insertCategory(w, null, "Antiga", "EXPENSE", true);
		UUID old = fixtures().insertLedgerTransaction(w, archivedAccount, s.fx().userId(), archivedCategory,
				"EXPENSE", 700, DAY, "Antigo", false);

		assertThat(remove(s.fx(), w, old).getResponse().getStatus()).isEqualTo(204);
		assertThat(row(old).get("deleted_at")).isNotNull();
	}

	@Test
	void foreignMissingOrMalformedIdsAreNotFoundAndChangeNothing() throws Exception {
		World s = world();
		World other = world();

		assertThat(remove(s.fx(), s.fx().workspaceId(), other.expense()).getResponse().getStatus()).isEqualTo(404);
		assertThat(remove(s.fx(), s.fx().workspaceId(), UUID.randomUUID()).getResponse().getStatus()).isEqualTo(404);
		mvc.perform(delete(base(s.fx().workspaceId()) + "/abc").cookie(session(s.fx().token())))
				.andExpect(status().isNotFound());
		assertThat(row(other.expense()).get("deleted_at")).isNull();
	}

	@Test
	void rolesAndMembershipDecideWhoCanDelete() throws Exception {
		World s = world();
		UUID w = s.fx().workspaceId();
		Fixture viewer = fixtures().addMember(w, "VIEWER");
		Fixture editor = fixtures().addMember(w, "EDITOR");
		Fixture stranger = fixtures().workspaceWithOwner();

		assertThat(remove(viewer, w, s.expense()).getResponse().getStatus()).isEqualTo(403);
		assertThat(remove(viewer, w, s.out()).getResponse().getStatus()).isEqualTo(403);
		MvcResult asStranger = remove(stranger, w, s.expense());
		MvcResult missing = remove(stranger, UUID.randomUUID(), s.expense());
		assertThat(asStranger.getResponse().getStatus()).isEqualTo(404);
		assertThat(asStranger.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", ""))
				.isEqualTo(missing.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", ""));
		assertThat(row(s.expense()).get("deleted_at")).isNull();
		assertThat(row(s.out()).get("deleted_at")).isNull();
		mvc.perform(delete(base(w) + "/" + s.expense())).andExpect(status().isUnauthorized());

		// o EDITOR exclui o que o dono criou
		assertThat(remove(editor, w, s.expense()).getResponse().getStatus()).isEqualTo(204);
		assertThat(remove(s.fx(), w, s.out()).getResponse().getStatus()).isEqualTo(204);
	}

	@Test
	void thereIsNoRestoreRouteAndNoOtherWayToRemoveTheRow() throws Exception {
		World s = world();
		remove(s.fx(), s.fx().workspaceId(), s.expense());

		int restore = mvc.perform(post(base(s.fx().workspaceId()) + "/" + s.expense() + "/restore")
				.cookie(session(s.fx().token()))).andReturn().getResponse().getStatus();

		assertThat(restore).isIn(404, 405);
		assertThat(owner.client().sql("select count(*) from transactions where id = :id").param("id", s.expense())
				.query(Long.class).single()).isEqualTo(1);
		assertThat(row(s.expense()).get("deleted_at")).isNotNull();
	}

}
