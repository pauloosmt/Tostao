package com.tostao.tostao_api.imports;

import static com.tostao.tostao_api.imports.ImportScenario.body;
import static com.tostao.tostao_api.imports.ImportScenario.confirm;
import static com.tostao.tostao_api.imports.ImportScenario.confirmUrl;
import static com.tostao.tostao_api.imports.ImportScenario.count;
import static com.tostao.tostao_api.imports.ImportScenario.fingerprint;
import static com.tostao.tostao_api.imports.ImportScenario.line;
import static com.tostao.tostao_api.imports.ImportScenario.preview;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/**
 * História 2: confirmar a importação (FR-012 a FR-016). Cada linha vira um lançamento efetivado da conta; qualquer linha
 * inválida recusa tudo sem gravar; a categoria é opcional e conferida.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ConfirmImportTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private ImportScenario scenario() {
		return ImportScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	private static String json(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	/** O corpo da confirmação com as linhas que a prévia do arquivo devolveu (sem categoria). */
	private String fromPreview(ImportScenario s, String file) throws Exception {
		String preview = json(preview(mvc, s.owner(), s.workspace(), s.checking(), file));
		List<Map<String, Object>> lines = JsonPath.read(preview, "$.lines");
		List<String> out = new ArrayList<>();
		for (Map<String, Object> l : lines) {
			out.add(line((String) l.get("key"), (String) l.get("occurredOn"), (String) l.get("description"),
					((Number) l.get("amountCents")).longValue(), null));
		}
		return body(out.toArray(String[]::new));
	}

	/** 400 apontando o campo, sem gravar nada. */
	private void invalid(ImportScenario s, String json, String field) throws Exception {
		String before = fingerprint(owner, s.workspace());

		confirm(mvc, s.owner(), s.workspace(), s.checking(), json).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value(field));

		assertThat(fingerprint(owner, s.workspace())).as("nada gravado: " + field).isEqualTo(before);
	}

	// ---- o que entra -------------------------------------------------------------------------------------------

	@Test
	void everyLineBecomesAClearedIncomeOrExpenseOfTheAccountAuthoredByThePerson() throws Exception {
		ImportScenario s = scenario();

		String response = json(confirm(mvc, s.owner(), s.workspace(), s.checking(), fromPreview(s, "banco.ofx"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.skipped").value(0))
				.andExpect(jsonPath("$.created.length()").value(4)));

		assertThat((List<Integer>) JsonPath.read(response, "$.created[*].amountCents")).containsExactly(500000, -2350, -18090, -2350);
		assertThat((List<String>) JsonPath.read(response, "$.created[*].type")).containsExactly("INCOME", "EXPENSE", "EXPENSE", "EXPENSE");
		assertThat((List<String>) JsonPath.read(response, "$.created[*].status")).containsOnly("CLEARED");
		assertThat((List<Boolean>) JsonPath.read(response, "$.created[*].imported")).containsOnly(true);
		assertThat((List<String>) JsonPath.read(response, "$.created[*].createdBy")).containsOnly(s.owner().userId().toString());
		assertThat((List<String>) JsonPath.read(response, "$.created[*].accountId")).containsOnly(s.checking().toString());
		assertThat((List<String>) JsonPath.read(response, "$.created[*].occurredOn")).containsExactly("2026-11-05", "2026-11-06",
				"2026-11-07", "2026-11-06");
		assertThat((List<String>) JsonPath.read(response, "$.created[*].description")).containsExactly("SALARIO EMPRESA XYZ",
				"PADARIA DO ZE", "MERCADO CENTRAL", "PADARIA DO ZE");
		assertThat((List<Object>) JsonPath.read(response, "$.created[*].categoryId")).containsOnlyNulls();
		assertThat((List<Object>) JsonPath.read(response, "$.created[*].transferGroupId")).containsOnlyNulls();
		assertThat((List<Object>) JsonPath.read(response, "$.created[*].autoClear")).containsOnly(false);
		assertThat((List<Object>) JsonPath.read(response, "$.created[*].notes")).containsOnlyNulls();
		assertThat(count(owner, s.checking())).isEqualTo(4);
	}

	@Test
	void theBalanceMovesByTheExactCents() throws Exception {
		ImportScenario s = scenario();

		confirm(mvc, s.owner(), s.workspace(), s.checking(), fromPreview(s, "banco.ofx")).andExpect(status().isCreated());

		// saldo inicial de 100.000 + 500.000 - 2.350 - 18.090 - 2.350
		mvc.perform(get("/api/v1/workspaces/" + s.workspace() + "/accounts/" + s.checking()).cookie(session(s.owner().token())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.balanceCents").value(577210));
	}

	@Test
	void theKeyIsStoredStrippedAndNeverReturned() throws Exception {
		ImportScenario s = scenario();

		String response = json(confirm(mvc, s.owner(), s.workspace(), s.checking(),
				body(line("  id:abc  ", "2026-11-05", "  Padaria  ", -100, null))).andExpect(status().isCreated()));

		assertThat(owner.client().sql("select import_key from transactions where account_id = :a").param("a", s.checking())
				.query(String.class).single()).isEqualTo("id:abc");
		assertThat(response).doesNotContain("id:abc").doesNotContainIgnoringCase("import_key").doesNotContainIgnoringCase("importKey");
		assertThat((String) JsonPath.read(response, "$.created[0].description")).isEqualTo("Padaria");
	}

	@Test
	void aCategoryOfTheRightKindIsKeptAndALineWithoutOneStaysWithout() throws Exception {
		ImportScenario s = scenario();

		String response = json(confirm(mvc, s.owner(), s.workspace(), s.checking(), body(
				line("k1", "2026-11-05", "Salário", 500000, s.salary()),
				line("k2", "2026-11-06", "Padaria", -2350, s.food()),
				line("k3", "2026-11-07", "Sem categoria", -100, null),
				line("k4", "2026-11-08", "Outra despesa", -200, s.food()))).andExpect(status().isCreated()));

		assertThat((List<String>) JsonPath.read(response, "$.created[*].categoryId")).containsExactly(s.salary().toString(),
				s.food().toString(), null, s.food().toString());
	}

	@Test
	void anEditorCanConfirmAndACreditCardAccountToo() throws Exception {
		ImportScenario s = scenario();
		UUID card = new WorkspaceFixtures(owner, tokens).insertCard(s.workspace(), "Cartão", 0, 500_000L, 5, 12, false);

		confirm(mvc, s.editor(), s.workspace(), s.checking(), body(line("a", "2026-11-05", "Editor", -100, null)))
				.andExpect(status().isCreated());
		confirm(mvc, s.owner(), s.workspace(), card, body(line("b", "2026-11-05", "Compra no cartão", -8990, s.food())))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.created[0].accountId").value(card.toString()));
	}

	@Test
	void theBoundariesAreAccepted() throws Exception {
		ImportScenario s = scenario();

		confirm(mvc, s.owner(), s.workspace(), s.checking(), body(
				line("a", "2000-01-01", "Limite inferior", -1, null),
				line("b", "2100-12-31", "Limite superior", 1_000_000_000_000L, null),
				line("c", "2026-11-05", "Maior despesa", -1_000_000_000_000L, null),
				line("d", "2030-01-01", "Data futura", -100, null))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.created.length()").value(4));
	}

	@Test
	void aThousandLinesAreAccepted() throws Exception {
		ImportScenario s = scenario();
		String[] lines = new String[1000];
		for (int i = 0; i < lines.length; i++) {
			lines[i] = line("bulk-" + i, "2026-11-05", "Compra " + i, -(i + 1), null);
		}

		confirm(mvc, s.owner(), s.workspace(), s.checking(), body(lines)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.created.length()").value(1000)).andExpect(jsonPath("$.skipped").value(0));

		assertThat(count(owner, s.checking())).isEqualTo(1000);
	}

	// ---- linha inválida recusa tudo ----------------------------------------------------------------------------

	@Test
	void everyInvalidFieldRefusesTheWholeConfirmationAndPointsAtTheLine() throws Exception {
		ImportScenario s = scenario();
		String ok = line("ok", "2026-11-05", "Boa", -100, null);

		invalid(s, body(ok, line("k", "1999-12-31", "Data antiga", -100, null)), "lines[1].occurredOn");
		invalid(s, body(ok, line("k", "2101-01-01", "Data futura demais", -100, null)), "lines[1].occurredOn");
		invalid(s, body(ok, line("k", "2026-11-05", "Zero", 0, null)), "lines[1].amountCents");
		invalid(s, body(ok, line("k", "2026-11-05", "Grande demais", 1_000_000_000_001L, null)), "lines[1].amountCents");
		invalid(s, body(ok, line("k", "2026-11-05", "Grande demais", -1_000_000_000_001L, null)), "lines[1].amountCents");
		invalid(s, body(ok, line("ok", "2026-11-06", "Chave repetida", -100, null)), "lines[1].key");
		invalid(s, body(line("k", "2026-11-05", "Boa", -100, null), ok, line("k2", "2026-11-05", "x\\u0000y", -100, null)), "lines[2].description");
		invalid(s, body(line("k\\u0000", "2026-11-05", "Chave com nulo", -100, null)), "lines[0].key");
		invalid(s, body(ok, line("k", "2026-11-05", "Receita em categoria de despesa", 100, s.food())), "lines[1].categoryId");
		invalid(s, body(ok, line("k", "2026-11-05", "Despesa em categoria de receita", -100, s.salary())), "lines[1].categoryId");
	}

	@Test
	void missingOrMalformedFieldsAreRefusedByTheDto() throws Exception {
		ImportScenario s = scenario();

		invalid(s, "{\"lines\":[]}", "lines");
		invalid(s, "{}", "lines");
		invalid(s, "{\"lines\":null}", "lines");
		invalid(s, "{\"lines\":[{\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"amountCents\":-1}]}", "lines[0].key");
		invalid(s, "{\"lines\":[{\"key\":\"\",\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"amountCents\":-1}]}", "lines[0].key");
		invalid(s, "{\"lines\":[{\"key\":\"   \",\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"amountCents\":-1}]}", "lines[0].key");
		invalid(s, "{\"lines\":[{\"key\":\"" + "k".repeat(201) + "\",\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"amountCents\":-1}]}", "lines[0].key");
		invalid(s, "{\"lines\":[{\"key\":\"k\",\"description\":\"x\",\"amountCents\":-1}]}", "lines[0].occurredOn");
		invalid(s, "{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"2026-11-05\",\"amountCents\":-1}]}", "lines[0].description");
		invalid(s, "{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"2026-11-05\",\"description\":\"  \",\"amountCents\":-1}]}", "lines[0].description");
		invalid(s, "{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"2026-11-05\",\"description\":\"" + "d".repeat(201) + "\",\"amountCents\":-1}]}", "lines[0].description");
		invalid(s, "{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"2026-11-05\",\"description\":\"x\"}]}", "lines[0].amountCents");
	}

	@Test
	void wrongTypesAndFieldsOutsideTheAllowedListAreRefused() throws Exception {
		ImportScenario s = scenario();
		String before = fingerprint(owner, s.workspace());
		String[] bodies = {
				"{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"2026-13-01\",\"description\":\"x\",\"amountCents\":-1}]}",
				"{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"05/11/2026\",\"description\":\"x\",\"amountCents\":-1}]}",
				"{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"amountCents\":-10.5}]}",
				"{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"amountCents\":\"abc\"}]}",
				"{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"amountCents\":-1,\"categoryId\":\"nao-uuid\"}]}",
				"{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"amountCents\":-1,\"type\":\"INCOME\"}]}",
				"{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"amountCents\":-1,\"createdBy\":\"" + UUID.randomUUID() + "\"}]}",
				"{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"amountCents\":-1,\"status\":\"PENDING\"}]}",
				"{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"amountCents\":-1,\"workspaceId\":\"" + s.workspace() + "\"}]}",
				"{\"lines\":[{\"key\":\"k\",\"occurredOn\":\"2026-11-05\",\"description\":\"x\",\"amountCents\":-1}],\"accountId\":\"" + s.savings() + "\"}",
				"{\"lines\":\"nao-e-lista\"}", "[]", "nem json", "" };

		for (String json : bodies) {
			confirm(mvc, s.owner(), s.workspace(), s.checking(), json).andExpect(status().isBadRequest());
		}
		mvc.perform(post(confirmUrl(s.workspace(), s.checking())).cookie(session(s.owner().token())).contentType(MediaType.TEXT_PLAIN)
				.content("x")).andExpect(status().isUnsupportedMediaType());
		assertThat(fingerprint(owner, s.workspace())).isEqualTo(before);
	}

	@Test
	void moreThanAThousandLinesAreRefused() throws Exception {
		ImportScenario s = scenario();
		String[] lines = new String[1001];
		for (int i = 0; i < lines.length; i++) {
			lines[i] = line("bulk-" + i, "2026-11-05", "Compra " + i, -(i + 1), null);
		}

		invalid(s, body(lines), "lines");
	}

	// ---- conta e categoria -------------------------------------------------------------------------------------

	@Test
	void anArchivedOrForeignOrMissingAccountIsRefusedAndNothingIsWritten() throws Exception {
		ImportScenario s = scenario();
		ImportScenario other = scenario();
		UUID archived = new WorkspaceFixtures(owner, tokens).insertAccount(s.workspace(), "Antiga", "CHECKING", 0, true);
		String one = body(line("a", "2026-11-05", "x", -100, null));
		String before = fingerprint(owner, s.workspace());

		confirm(mvc, s.owner(), s.workspace(), archived, one).andExpect(status().isConflict());
		confirm(mvc, s.owner(), s.workspace(), other.checking(), one).andExpect(status().isNotFound());
		confirm(mvc, s.owner(), s.workspace(), UUID.randomUUID(), one).andExpect(status().isNotFound());
		confirm(mvc, s.owner(), s.workspace(), "abc", one).andExpect(status().isNotFound());

		assertThat(fingerprint(owner, s.workspace())).isEqualTo(before);
		assertThat(count(owner, other.checking())).isZero();
	}

	@Test
	void aMissingForeignOrArchivedCategoryIsRefusedAndNothingIsWritten() throws Exception {
		ImportScenario s = scenario();
		ImportScenario other = scenario();
		UUID archived = new WorkspaceFixtures(owner, tokens).insertCategory(s.workspace(), null, "Antiga", "EXPENSE", true);
		String before = fingerprint(owner, s.workspace());

		confirm(mvc, s.owner(), s.workspace(), s.checking(), body(line("ok", "2026-11-05", "Boa", -100, null),
				line("a", "2026-11-05", "x", -100, UUID.randomUUID()))).andExpect(status().isNotFound());
		confirm(mvc, s.owner(), s.workspace(), s.checking(), body(line("a", "2026-11-05", "x", -100, other.food())))
				.andExpect(status().isNotFound());
		confirm(mvc, s.owner(), s.workspace(), s.checking(), body(line("ok", "2026-11-05", "Boa", -100, null),
				line("a", "2026-11-05", "x", -100, archived))).andExpect(status().isConflict());

		assertThat(fingerprint(owner, s.workspace())).isEqualTo(before);
	}

	// ---- atomicidade -------------------------------------------------------------------------------------------

	@Test
	void aFailureInTheMiddleLeavesNothingWritten() throws Exception {
		ImportScenario s = scenario();
		// um gatilho de teste faz a inserção falhar numa linha do segundo bloco de 100
		owner.client().sql("""
				create or replace function import_test_boom() returns trigger language plpgsql as $f$
				begin
				  if new.description = 'EXPLODIR-NO-TESTE' then raise exception 'falha de teste'; end if;
				  return new;
				end $f$""").update();
		owner.client().sql("drop trigger if exists import_test_boom on transactions").update();
		owner.client().sql("create trigger import_test_boom before insert on transactions for each row execute function import_test_boom()").update();
		try {
			String[] lines = new String[150];
			for (int i = 0; i < lines.length; i++) {
				lines[i] = line("atomic-" + i, "2026-11-05", i == 120 ? "EXPLODIR-NO-TESTE" : "Compra " + i, -(i + 1), null);
			}

			assertThatThrownBy(() -> confirm(mvc, s.owner(), s.workspace(), s.checking(), body(lines)))
					.rootCause().hasMessageContaining("falha de teste");
		}
		finally {
			owner.client().sql("drop trigger if exists import_test_boom on transactions").update();
		}

		assertThat(count(owner, s.checking())).as("o primeiro bloco de 100 linhas não pode ter ficado").isZero();
	}

}
