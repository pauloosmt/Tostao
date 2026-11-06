package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/** Acesso aos lançamentos por papel e por workspace: quem não é membro não descobre nada. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class TransactionAccessTest {

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

	/** Corpo sem o campo {@code instance}, que repete a rota pedida e por isso muda de um caso para outro. */
	private static String withoutInstance(MvcResult result) throws Exception {
		return result.getResponse().getContentAsString().replaceAll("\"instance\":\"[^\"]*\",?", "");
	}

	private MvcResult createAs(Fixture who, UUID workspaceId, String json) throws Exception {
		return mvc.perform(post(base(workspaceId)).cookie(session(who.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
	}

	private long count(UUID workspaceId) {
		return owner.client().sql("select count(*) from transactions where workspace_id = :w").param("w", workspaceId)
				.query(Long.class).single();
	}

	/** Workspace com uma conta, uma poupança, categorias e um lançamento já existente. */
	private record World(Fixture owner, UUID account, UUID savings, UUID expense, UUID income, UUID existing) {

		String expenseBody() {
			return "{\"type\":\"EXPENSE\",\"accountId\":\"%s\",\"amountCents\":100,\"occurredOn\":\"2026-11-05\",\"description\":\"Novo\",\"categoryId\":\"%s\"}"
					.formatted(account, expense);
		}

		String transferBody() {
			return "{\"type\":\"TRANSFER\",\"accountId\":\"%s\",\"destinationAccountId\":\"%s\",\"amountCents\":100,\"occurredOn\":\"2026-11-05\",\"description\":\"Mover\"}"
					.formatted(account, savings);
		}
	}

	private World world() {
		Fixture ownerFx = fixtures().workspaceWithOwner();
		UUID w = ownerFx.workspaceId();
		UUID account = fixtures().insertAccount(w, "Corrente", "CHECKING", 0, false);
		UUID savings = fixtures().insertAccount(w, "Poupanca", "SAVINGS", 0, false);
		UUID expense = fixtures().insertCategory(w, null, "Mercado", "EXPENSE", false);
		UUID income = fixtures().insertCategory(w, null, "Salario", "INCOME", false);
		UUID existing = fixtures().insertLedgerTransaction(w, account, ownerFx.userId(), expense, "EXPENSE", 500,
				LocalDate.of(2026, 11, 1), "Privado", false);
		return new World(ownerFx, account, savings, expense, income, existing);
	}

	@Test
	void aNonMemberGetsTheSameAnswerAsForAMissingWorkspace() throws Exception {
		World wd = world();
		Fixture stranger = fixtures().workspaceWithOwner();
		UUID missing = UUID.randomUUID();
		long before = count(wd.owner().workspaceId());

		MvcResult list = mvc.perform(get(base(wd.owner().workspaceId())).cookie(session(stranger.token()))).andReturn();
		MvcResult listMissing = mvc.perform(get(base(missing)).cookie(session(stranger.token()))).andReturn();
		MvcResult one = mvc.perform(get(base(wd.owner().workspaceId()) + "/" + wd.existing())
				.cookie(session(stranger.token()))).andReturn();
		MvcResult oneMissing = mvc.perform(get(base(missing) + "/" + wd.existing()).cookie(session(stranger.token())))
				.andReturn();
		MvcResult expense = createAs(stranger, wd.owner().workspaceId(), wd.expenseBody());
		MvcResult expenseMissing = createAs(stranger, missing, wd.expenseBody());
		MvcResult transfer = createAs(stranger, wd.owner().workspaceId(), wd.transferBody());
		MvcResult transferMissing = createAs(stranger, missing, wd.transferBody());

		for (MvcResult result : new MvcResult[] { list, one, expense, transfer }) {
			assertThat(result.getResponse().getStatus()).isEqualTo(404);
			assertThat(result.getResponse().getContentAsString()).doesNotContain("Privado");
		}
		assertThat(withoutInstance(list)).isEqualTo(withoutInstance(listMissing));
		assertThat(withoutInstance(one)).isEqualTo(withoutInstance(oneMissing));
		assertThat(withoutInstance(expense)).isEqualTo(withoutInstance(expenseMissing));
		assertThat(withoutInstance(transfer)).isEqualTo(withoutInstance(transferMissing));
		assertThat(count(wd.owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void viewerCanReadButNotCreate() throws Exception {
		World wd = world();
		Fixture viewer = fixtures().addMember(wd.owner().workspaceId(), "VIEWER");
		long before = count(wd.owner().workspaceId());

		mvc.perform(get(base(wd.owner().workspaceId())).cookie(session(viewer.token()))).andExpect(status().isOk());
		mvc.perform(get(base(wd.owner().workspaceId()) + "/" + wd.existing()).cookie(session(viewer.token())))
				.andExpect(status().isOk());
		assertThat(createAs(viewer, wd.owner().workspaceId(), wd.expenseBody()).getResponse().getStatus())
				.isEqualTo(403);
		assertThat(createAs(viewer, wd.owner().workspaceId(), wd.transferBody()).getResponse().getStatus())
				.isEqualTo(403);
		assertThat(count(wd.owner().workspaceId())).isEqualTo(before);
	}

	@Test
	void editorAndOwnerCreateAndTheAuthorIsTheSessionUser() throws Exception {
		World wd = world();
		Fixture editor = fixtures().addMember(wd.owner().workspaceId(), "EDITOR");

		MvcResult byEditor = createAs(editor, wd.owner().workspaceId(), wd.expenseBody());
		MvcResult byOwner = createAs(wd.owner(), wd.owner().workspaceId(), wd.transferBody());

		assertThat(byEditor.getResponse().getStatus()).isEqualTo(201);
		assertThat(byOwner.getResponse().getStatus()).isEqualTo(201);
		assertThat((String) JsonPath.read(byEditor.getResponse().getContentAsString(), "$.createdBy"))
				.isEqualTo(editor.userId().toString());
		assertThat((String) JsonPath.read(byOwner.getResponse().getContentAsString(), "$.createdBy"))
				.isEqualTo(wd.owner().userId().toString());
	}

	@Test
	void aTransactionOfAnotherWorkspaceIsNotFoundThroughMyRoute() throws Exception {
		World mine = world();
		World other = world();

		mvc.perform(get(base(mine.owner().workspaceId()) + "/" + other.existing()).cookie(session(mine.owner().token())))
				.andExpect(status().isNotFound());
	}

	@Test
	void anAccountOrCategoryOfAnotherWorkspaceCannotBeUsed() throws Exception {
		World mine = world();
		World other = world();
		long before = count(mine.owner().workspaceId());

		String foreignAccount = mine.expenseBody().replace(mine.account().toString(), other.account().toString());
		String foreignCategory = mine.expenseBody().replace(mine.expense().toString(), other.expense().toString());
		String foreignDestination = mine.transferBody().replace(mine.savings().toString(), other.savings().toString());

		for (String body : new String[] { foreignAccount, foreignCategory, foreignDestination }) {
			assertThat(createAs(mine.owner(), mine.owner().workspaceId(), body).getResponse().getStatus()).isEqualTo(404);
		}
		assertThat(count(mine.owner().workspaceId())).isEqualTo(before);
		assertThat(count(other.owner().workspaceId())).isEqualTo(1);
	}

	@Test
	void withoutASessionTheAnswerIsUnauthorized() throws Exception {
		World wd = world();

		mvc.perform(get(base(wd.owner().workspaceId()))).andExpect(status().isUnauthorized());
		mvc.perform(get(base(wd.owner().workspaceId()) + "/" + wd.existing())).andExpect(status().isUnauthorized());
		mvc.perform(post(base(wd.owner().workspaceId())).contentType(MediaType.APPLICATION_JSON)
				.content(wd.expenseBody())).andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedAnswersAreNeverCached() throws Exception {
		World wd = world();

		MvcResult result = mvc.perform(get(base(wd.owner().workspaceId())).cookie(session(wd.owner().token())))
				.andExpect(status().isOk()).andReturn();

		assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
	}

}
