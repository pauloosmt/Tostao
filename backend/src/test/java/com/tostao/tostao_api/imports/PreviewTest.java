package com.tostao.tostao_api.imports;

import static com.tostao.tostao_api.imports.ImportScenario.fingerprint;
import static com.tostao.tostao_api.imports.ImportScenario.preview;
import static com.tostao.tostao_api.imports.ImportScenario.previewUrl;
import static com.tostao.tostao_api.imports.ImportScenario.seed;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
import com.tostao.tostao_api.support.WorkspaceFixtures;

/**
 * História 1: a prévia do extrato (FR-001 a FR-011). Lê OFX 1, OFX 2, CSV e cartão, marca o que já existe, o que pode ser
 * duplicata e a categoria sugerida, aponta as linhas com problema, recusa o arquivo que não dá para ler e <b>nunca grava</b>.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PreviewTest {

	private static final LocalDate NOV_6 = LocalDate.of(2026, 11, 6);

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

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	// ---- leitura dos formatos ---------------------------------------------------------------------------------

	@Test
	void previewsAnOfx1StatementWithEveryFieldAndWritesNothing() throws Exception {
		ImportScenario s = scenario();
		String before = fingerprint(owner, s.workspace());

		preview(mvc, s.owner(), s.workspace(), s.checking(), "banco.ofx").andExpect(status().isOk())
				.andExpect(jsonPath("$.format").value("OFX"))
				.andExpect(jsonPath("$.total").value(4)).andExpect(jsonPath("$.valid").value(4))
				.andExpect(jsonPath("$.existing").value(0)).andExpect(jsonPath("$.invalid").value(0))
				.andExpect(jsonPath("$.lines.length()").value(4))
				.andExpect(jsonPath("$.lines[0].line").value(1))
				.andExpect(jsonPath("$.lines[0].occurredOn").value("2026-11-05"))
				.andExpect(jsonPath("$.lines[0].description").value("SALARIO EMPRESA XYZ"))
				.andExpect(jsonPath("$.lines[0].amountCents").value(500000))
				.andExpect(jsonPath("$.lines[0].type").value("INCOME"))
				.andExpect(jsonPath("$.lines[0].key").value("id:1"))
				.andExpect(jsonPath("$.lines[0].existing").value(false))
				.andExpect(jsonPath("$.lines[0].possibleDuplicate").value(false))
				.andExpect(jsonPath("$.lines[0].suggestedCategoryId").doesNotExist())
				.andExpect(jsonPath("$.lines[0].problems.length()").value(0))
				.andExpect(jsonPath("$.lines[1].description").value("PADARIA DO ZE"))
				.andExpect(jsonPath("$.lines[1].amountCents").value(-2350))
				.andExpect(jsonPath("$.lines[1].type").value("EXPENSE"))
				.andExpect(jsonPath("$.lines[2].amountCents").value(-18090))
				.andExpect(jsonPath("$.lines[3].key").value("id:4"));

		assertThat(fingerprint(owner, s.workspace())).as("a prévia não grava").isEqualTo(before);
	}

	@Test
	void theSameMovementsComeOutOfOfx2AndCsvToo() throws Exception {
		ImportScenario s = scenario();

		for (String file : new String[] { "banco2.ofx", "banco.csv" }) {
			String body = json(preview(mvc, s.owner(), s.workspace(), s.checking(), file).andExpect(status().isOk()));

			assertThat((List<Integer>) JsonPath.read(body, "$.lines[*].amountCents")).as(file).containsExactly(500000, -2350, -18090, -2350);
			assertThat((List<String>) JsonPath.read(body, "$.lines[*].occurredOn")).as(file).containsExactly("2026-11-05",
					"2026-11-06", "2026-11-07", "2026-11-06");
			assertThat((String) JsonPath.read(body, "$.format")).isEqualTo(file.endsWith("ofx") ? "OFX" : "CSV");
		}
	}

	@Test
	void aCreditCardStatementIsReadTheSameWay() throws Exception {
		ImportScenario s = scenario();

		preview(mvc, s.owner(), s.workspace(), s.checking(), "cartao.ofx").andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.lines[0].type").value("EXPENSE"))
				.andExpect(jsonPath("$.lines[2].type").value("INCOME")).andExpect(jsonPath("$.lines[2].key").value("id:C3"));
	}

	@Test
	void csvWithoutAnIdentifierGivesDerivedKeysAndTwoIdenticalLinesKeepApart() throws Exception {
		ImportScenario s = scenario();

		String body = json(preview(mvc, s.owner(), s.workspace(), s.checking(), "banco.csv").andExpect(status().isOk()));
		List<String> keys = JsonPath.read(body, "$.lines[*].key");

		assertThat(keys).allSatisfy(key -> assertThat(key).matches("h:[0-9a-f]{64}")).doesNotHaveDuplicates();
	}

	@Test
	void theKeysDoNotChangeBetweenTwoPreviewsOfTheSameFile() throws Exception {
		ImportScenario s = scenario();

		List<String> first = JsonPath.read(json(preview(mvc, s.owner(), s.workspace(), s.checking(), "banco.csv")), "$.lines[*].key");
		List<String> second = JsonPath.read(json(preview(mvc, s.owner(), s.workspace(), s.checking(), "banco.csv")), "$.lines[*].key");

		assertThat(second).containsExactlyElementsOf(first);
	}

	// ---- linhas com problema ----------------------------------------------------------------------------------

	@Test
	void badLinesCarryTheirReasonAndDoNotStopTheOthers() throws Exception {
		ImportScenario s = scenario();
		String csv = "Data;Descrição;Valor\n05/11/2026;Boa;-10,00\n31/02/2026;Data ruim;-1,00\n05/11/2026;Valor ruim;abc\n"
				+ "05/11/2026;;-2,00\n05/11/1999;Antiga;-3,00\n06/11/2026;Zero;0,00\n07/11/2026;Outra boa;5,00\n";

		preview(mvc, s.owner(), s.workspace(), s.checking(), bytes(csv)).andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(7)).andExpect(jsonPath("$.valid").value(2)).andExpect(jsonPath("$.invalid").value(5))
				.andExpect(jsonPath("$.lines[0].problems.length()").value(0))
				.andExpect(jsonPath("$.lines[1].problems[0]").value("data inválida"))
				.andExpect(jsonPath("$.lines[1].line").value(3))
				.andExpect(jsonPath("$.lines[1].key").doesNotExist())
				.andExpect(jsonPath("$.lines[1].occurredOn").doesNotExist())
				.andExpect(jsonPath("$.lines[2].problems[0]").value("valor ilegível"))
				.andExpect(jsonPath("$.lines[2].amountCents").doesNotExist())
				.andExpect(jsonPath("$.lines[2].type").doesNotExist())
				.andExpect(jsonPath("$.lines[3].problems[0]").value("descrição vazia"))
				.andExpect(jsonPath("$.lines[4].problems[0]").value("data fora de 2000-01-01 a 2100-12-31"))
				.andExpect(jsonPath("$.lines[5].problems[0]").value("valor zero"))
				.andExpect(jsonPath("$.lines[6].problems.length()").value(0))
				.andExpect(jsonPath("$.lines[6].type").value("INCOME"));
	}

	@Test
	void theDescriptionComesCleanAndCutAtTwoHundred() throws Exception {
		ImportScenario s = scenario();
		String csv = "Data;Descrição;Valor\n05/11/2026;  Padaria    do   Zé ;-1,00\n05/11/2026;" + "z".repeat(260) + ";-2,00\n";

		String body = json(preview(mvc, s.owner(), s.workspace(), s.checking(), bytes(csv)).andExpect(status().isOk()));

		assertThat((String) JsonPath.read(body, "$.lines[0].description")).isEqualTo("Padaria do Zé");
		assertThat((String) JsonPath.read(body, "$.lines[1].description")).isEqualTo("z".repeat(200));
	}

	// ---- o que já existe e as possíveis duplicatas -------------------------------------------------------------

	@Test
	void linesWhoseKeyAlreadyExistInTheAccountAreMarkedEvenWhenTheRowWasDeleted() throws Exception {
		ImportScenario s = scenario();
		UUID w = s.workspace();
		seed(owner, w, s.checking(), s.owner().userId(), null, -2350, NOV_6, "PADARIA DO ZE", "id:2", false);
		seed(owner, w, s.checking(), s.owner().userId(), null, -18090, LocalDate.of(2026, 11, 7), "MERCADO", "id:3", true);
		// a mesma chave em outra conta não conta
		seed(owner, w, s.savings(), s.owner().userId(), null, 500000, LocalDate.of(2026, 11, 5), "SALARIO", "id:1", false);

		preview(mvc, s.owner(), w, s.checking(), "banco.ofx").andExpect(status().isOk())
				.andExpect(jsonPath("$.existing").value(2)).andExpect(jsonPath("$.valid").value(2))
				.andExpect(jsonPath("$.lines[0].existing").value(false))
				.andExpect(jsonPath("$.lines[1].existing").value(true))
				.andExpect(jsonPath("$.lines[2].existing").value(true))
				.andExpect(jsonPath("$.lines[3].existing").value(false));
	}

	@Test
	void aHandTypedTransactionWithTheSameDateAndAmountIsAPossibleDuplicate() throws Exception {
		ImportScenario s = scenario();
		UUID w = s.workspace();
		UUID user = s.owner().userId();
		seed(owner, w, s.checking(), user, s.food(), -2350, NOV_6, "padaria", null, false);
		// não casam: data diferente, valor diferente, conta diferente, excluído, já importado
		seed(owner, w, s.checking(), user, s.food(), -18090, LocalDate.of(2026, 11, 8), "mercado", null, false);
		seed(owner, w, s.checking(), user, s.food(), -18091, LocalDate.of(2026, 11, 7), "mercado", null, false);
		seed(owner, w, s.savings(), user, s.food(), -18090, LocalDate.of(2026, 11, 7), "mercado", null, false);
		seed(owner, w, s.checking(), user, s.food(), -18090, LocalDate.of(2026, 11, 7), "mercado", null, true);
		seed(owner, w, s.checking(), user, s.food(), -18090, LocalDate.of(2026, 11, 7), "mercado", "id:zzz", false);

		preview(mvc, s.owner(), w, s.checking(), "banco.ofx").andExpect(status().isOk())
				.andExpect(jsonPath("$.lines[0].possibleDuplicate").value(false))
				.andExpect(jsonPath("$.lines[1].possibleDuplicate").value(true))
				.andExpect(jsonPath("$.lines[2].possibleDuplicate").value(false))
				.andExpect(jsonPath("$.lines[3].possibleDuplicate").value(true))
				// é só um aviso: as linhas seguem válidas e importáveis
				.andExpect(jsonPath("$.valid").value(4)).andExpect(jsonPath("$.lines[1].existing").value(false));
	}

	// ---- sugestão de categoria ---------------------------------------------------------------------------------

	@Test
	void theSuggestedCategoryIsTheOneOfTheLatestTransactionWithTheSameDescription() throws Exception {
		ImportScenario s = scenario();
		UUID w = s.workspace();
		UUID user = s.owner().userId();
		// descrição igual sem diferença de caixa, acentos e espaços; a mais recente decide
		seed(owner, w, s.checking(), user, s.transport(), -1000, LocalDate.of(2026, 9, 1), "Padaria do Zé", null, false);
		seed(owner, w, s.savings(), user, s.food(), -1100, LocalDate.of(2026, 10, 1), "PADARIA   DO ZE", null, false);
		seed(owner, w, s.checking(), user, s.salary(), 500000, LocalDate.of(2026, 10, 5), "salario empresa xyz", null, false);

		String body = json(preview(mvc, s.owner(), w, s.checking(), "banco.ofx").andExpect(status().isOk()));

		assertThat((String) JsonPath.read(body, "$.lines[1].suggestedCategoryId")).isEqualTo(s.food().toString());
		assertThat((String) JsonPath.read(body, "$.lines[3].suggestedCategoryId")).isEqualTo(s.food().toString());
		assertThat((String) JsonPath.read(body, "$.lines[0].suggestedCategoryId")).isEqualTo(s.salary().toString());
		assertThat((Object) JsonPath.read(body, "$.lines[2].suggestedCategoryId")).as("MERCADO CENTRAL nunca foi lançado").isNull();
	}

	@Test
	void theSuggestionTiesAreBrokenByDateThenByCreation() throws Exception {
		ImportScenario s = scenario();
		UUID w = s.workspace();
		UUID user = s.owner().userId();
		seed(owner, w, s.checking(), user, s.transport(), -1000, NOV_6, "Padaria do Zé", null, false);
		seed(owner, w, s.checking(), user, s.food(), -1000, NOV_6, "Padaria do Zé", null, false);

		String body = json(preview(mvc, s.owner(), w, s.checking(), "banco.ofx").andExpect(status().isOk()));

		// mesma data: vale o criado por último
		assertThat((String) JsonPath.read(body, "$.lines[1].suggestedCategoryId")).isEqualTo(s.food().toString());
	}

	@Test
	void neverSuggestsAnArchivedCategoryADeletedTransactionAnotherTypeOrAnotherWorkspace() throws Exception {
		ImportScenario s = scenario();
		ImportScenario other = scenario();
		UUID w = s.workspace();
		UUID user = s.owner().userId();
		WorkspaceFixtures f = new WorkspaceFixtures(owner, tokens);
		UUID archived = f.insertCategory(w, null, "Antiga", "EXPENSE", true);
		// a mais recente tem categoria arquivada e a excluída é ignorada: sobra a mais antiga, ativa
		seed(owner, w, s.checking(), user, s.transport(), -1000, LocalDate.of(2026, 8, 1), "Padaria do Zé", null, false);
		seed(owner, w, s.checking(), user, archived, -1000, LocalDate.of(2026, 10, 1), "Padaria do Zé", null, false);
		seed(owner, w, s.checking(), user, s.food(), -1000, LocalDate.of(2026, 10, 2), "Padaria do Zé", null, true);
		// o tipo é outro (receita com a mesma descrição) e o workspace é outro
		seed(owner, w, s.checking(), user, s.salary(), 1000, LocalDate.of(2026, 10, 3), "Padaria do Zé", null, false);
		seed(owner, other.workspace(), other.checking(), other.owner().userId(), other.food(), -1000, LocalDate.of(2026, 10, 4),
				"Mercado Central", null, false);

		String body = json(preview(mvc, s.owner(), w, s.checking(), "banco.ofx").andExpect(status().isOk()));

		assertThat((String) JsonPath.read(body, "$.lines[1].suggestedCategoryId")).isEqualTo(s.transport().toString());
		assertThat((Object) JsonPath.read(body, "$.lines[2].suggestedCategoryId")).as("a de outro workspace não vaza").isNull();
	}

	// ---- recusas -----------------------------------------------------------------------------------------------

	@Test
	void anUnreadableFileIsRefusedAsAWholeIndicatingTheFile() throws Exception {
		ImportScenario s = scenario();
		String before = fingerprint(owner, s.workspace());

		for (byte[] bad : new byte[][] { bytes("isto não é um extrato\nnem tem colunas"), bytes("{\"json\":true}"), new byte[0],
				bytes("   \n  "), bytes("Data;Descrição;Valor\n"), bytes("<OFX></OFX>") }) {
			preview(mvc, s.owner(), s.workspace(), s.checking(), bad).andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("file")).andExpect(jsonPath("$.lines").doesNotExist());
		}
		assertThat(fingerprint(owner, s.workspace())).isEqualTo(before);
	}

	@Test
	void tooManyMovementsOrTooBigAFileAreRefused() throws Exception {
		ImportScenario s = scenario();
		StringBuilder many = new StringBuilder("Data;Descrição;Valor\n");
		for (int i = 0; i <= 1000; i++) {
			many.append("05/11/2026;Compra ").append(i).append(";-1,00\n");
		}

		preview(mvc, s.owner(), s.workspace(), s.checking(), bytes(many.toString())).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("file"));
		preview(mvc, s.owner(), s.workspace(), s.checking(), new byte[StatementParser.MAX_BYTES + 1]).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("file"));
	}

	@Test
	void aRequestWithoutTheFilePartPointsAtTheFile() throws Exception {
		ImportScenario s = scenario();

		mvc.perform(multipart(previewUrl(s.workspace(), s.checking())).cookie(session(s.owner().token()))).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors[0].field").value("file"));
	}

	@Test
	void anArchivedAccountAndAnotherWorkspacesAccountAreRefused() throws Exception {
		ImportScenario s = scenario();
		ImportScenario other = scenario();
		UUID archived = new WorkspaceFixtures(owner, tokens).insertAccount(s.workspace(), "Antiga", "CHECKING", 0, true);

		preview(mvc, s.owner(), s.workspace(), archived, "banco.ofx").andExpect(status().isConflict());
		preview(mvc, s.owner(), s.workspace(), other.checking(), "banco.ofx").andExpect(status().isNotFound());
		preview(mvc, s.owner(), s.workspace(), UUID.randomUUID(), "banco.ofx").andExpect(status().isNotFound());
		preview(mvc, s.owner(), s.workspace(), "abc", "banco.ofx").andExpect(status().isNotFound());
	}

	@Test
	void theErrorsNeverQuoteTheFileContent() throws Exception {
		ImportScenario s = scenario();
		String secret = "SEGREDO-DA-DESCRICAO-777";

		String body = json(preview(mvc, s.owner(), s.workspace(), s.checking(), bytes(secret + "\n" + secret)).andExpect(status().isBadRequest()));
		String lines = json(preview(mvc, s.owner(), s.workspace(), s.checking(),
				bytes("Data;Descrição;Valor\n05/11/2026;" + secret + ";abc\n")).andExpect(status().isOk()));

		assertThat(body).doesNotContain(secret);
		// numa linha com problema o texto da própria linha aparece (é a prévia dela), mas o motivo não o cita
		assertThat((String) JsonPath.read(lines, "$.lines[0].problems[0]")).doesNotContain(secret);
	}

}
