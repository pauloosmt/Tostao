package com.tostao.tostao_api.imports;

import static com.tostao.tostao_api.imports.ImportScenario.body;
import static com.tostao.tostao_api.imports.ImportScenario.confirm;
import static com.tostao.tostao_api.imports.ImportScenario.confirmUrl;
import static com.tostao.tostao_api.imports.ImportScenario.count;
import static com.tostao.tostao_api.imports.ImportScenario.fingerprint;
import static com.tostao.tostao_api.imports.ImportScenario.line;
import static com.tostao.tostao_api.imports.ImportScenario.preview;
import static com.tostao.tostao_api.imports.ImportScenario.previewUrl;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/**
 * História 4: só quem pode importa, e tudo fica no workspace (FR-020, FR-021, SC-008, SC-009). Papéis, acesso cruzado e
 * a ordem das respostas: sessão, participação e papel, conta e só então o arquivo.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ImportAccessTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private ImportScenario scenario() {
		return ImportScenario.create(new WorkspaceFixtures(owner, tokens));
	}

	private static final String ONE = body(line("a", "2026-11-05", "Compra", -100, null));

	@Test
	void ownerAndEditorCanPreviewAndConfirm() throws Exception {
		ImportScenario s = scenario();

		for (var who : new com.tostao.tostao_api.support.WorkspaceFixtures.Fixture[] { s.owner(), s.editor() }) {
			preview(mvc, who, s.workspace(), s.checking(), "banco.ofx").andExpect(status().isOk());
		}
		confirm(mvc, s.owner(), s.workspace(), s.checking(), ONE).andExpect(status().isCreated());
		confirm(mvc, s.editor(), s.workspace(), s.checking(), body(line("b", "2026-11-05", "Outra", -100, null))).andExpect(status().isCreated());
	}

	@Test
	void aViewerIsForbiddenOnBothRoutesAndNothingIsWritten() throws Exception {
		ImportScenario s = scenario();
		String before = fingerprint(owner, s.workspace());

		preview(mvc, s.viewer(), s.workspace(), s.checking(), "banco.ofx").andExpect(status().isForbidden());
		confirm(mvc, s.viewer(), s.workspace(), s.checking(), ONE).andExpect(status().isForbidden());
		// com arquivo ilegível o leitor também recebe 403, nunca 400: o papel vem antes do arquivo
		preview(mvc, s.viewer(), s.workspace(), s.checking(), "lixo".getBytes(StandardCharsets.UTF_8)).andExpect(status().isForbidden());

		assertThat(fingerprint(owner, s.workspace())).isEqualTo(before);
	}

	@Test
	void aNonMemberGetsTheSameNotFoundAsAMissingWorkspaceOnBothRoutes() throws Exception {
		ImportScenario s = scenario();
		String before = fingerprint(owner, s.workspace());
		UUID missing = UUID.randomUUID();

		var previewReal = preview(mvc, s.outsider(), s.workspace(), s.checking(), "banco.ofx").andExpect(status().isNotFound());
		var previewMissing = preview(mvc, s.outsider(), missing, s.checking(), "banco.ofx").andExpect(status().isNotFound());
		var confirmReal = confirm(mvc, s.outsider(), s.workspace(), s.checking(), ONE).andExpect(status().isNotFound());
		var confirmMissing = confirm(mvc, s.outsider(), missing, s.checking(), ONE).andExpect(status().isNotFound());

		assertThat((String) JsonPath.read(previewReal.andReturn().getResponse().getContentAsString(), "$.detail"))
				.isEqualTo(JsonPath.read(previewMissing.andReturn().getResponse().getContentAsString(), "$.detail"));
		assertThat((String) JsonPath.read(confirmReal.andReturn().getResponse().getContentAsString(), "$.detail"))
				.isEqualTo(JsonPath.read(confirmMissing.andReturn().getResponse().getContentAsString(), "$.detail"));
		// com arquivo ilegível o de fora também recebe 404, e não 400
		preview(mvc, s.outsider(), s.workspace(), s.checking(), "lixo".getBytes(StandardCharsets.UTF_8)).andExpect(status().isNotFound());
		assertThat(fingerprint(owner, s.workspace())).isEqualTo(before);
	}

	@Test
	void withoutASessionBothRoutesAre401() throws Exception {
		ImportScenario s = scenario();
		String before = fingerprint(owner, s.workspace());

		mvc.perform(multipart(previewUrl(s.workspace(), s.checking()))
				.file(new MockMultipartFile("file", "extrato", "application/octet-stream", Statements.bytes("banco.ofx"))))
				.andExpect(status().isUnauthorized());
		mvc.perform(post(confirmUrl(s.workspace(), s.checking())).contentType(MediaType.APPLICATION_JSON).content(ONE))
				.andExpect(status().isUnauthorized());

		assertThat(fingerprint(owner, s.workspace())).isEqualTo(before);
	}

	@Test
	void anAccountOfAnotherWorkspaceIsNotFoundInMineAndMineIsNotFoundInTheirs() throws Exception {
		ImportScenario mine = scenario();
		ImportScenario theirs = scenario();
		String myBefore = fingerprint(owner, mine.workspace());
		String theirBefore = fingerprint(owner, theirs.workspace());

		// a conta deles na rota do meu workspace
		preview(mvc, mine.owner(), mine.workspace(), theirs.checking(), "banco.ofx").andExpect(status().isNotFound());
		confirm(mvc, mine.owner(), mine.workspace(), theirs.checking(), ONE).andExpect(status().isNotFound());
		// a minha conta na rota do workspace deles: não sou membro
		preview(mvc, mine.owner(), theirs.workspace(), mine.checking(), "banco.ofx").andExpect(status().isNotFound());
		confirm(mvc, mine.owner(), theirs.workspace(), mine.checking(), ONE).andExpect(status().isNotFound());
		// e a conta deles na rota do workspace deles também não me serve
		confirm(mvc, mine.owner(), theirs.workspace(), theirs.checking(), ONE).andExpect(status().isNotFound());

		assertThat(fingerprint(owner, mine.workspace())).isEqualTo(myBefore);
		assertThat(fingerprint(owner, theirs.workspace())).isEqualTo(theirBefore);
	}

	@Test
	void aCategoryOfAnotherWorkspaceIsNotFoundAndNothingIsWritten() throws Exception {
		ImportScenario mine = scenario();
		ImportScenario theirs = scenario();
		String before = fingerprint(owner, mine.workspace());

		confirm(mvc, mine.owner(), mine.workspace(), mine.checking(), body(line("a", "2026-11-05", "x", -100, theirs.food())))
				.andExpect(status().isNotFound());

		assertThat(fingerprint(owner, mine.workspace())).isEqualTo(before);
	}

	@Test
	void malformedIdsAreNotFoundOnBothRoutes() throws Exception {
		ImportScenario s = scenario();

		preview(mvc, s.owner(), s.workspace(), "abc", "banco.ofx").andExpect(status().isNotFound());
		confirm(mvc, s.owner(), s.workspace(), "abc", ONE).andExpect(status().isNotFound());
		mvc.perform(multipart("/api/v1/workspaces/abc/accounts/" + s.checking() + "/import/preview")
				.file(new MockMultipartFile("file", "extrato", "application/octet-stream", Statements.bytes("banco.ofx")))
				.cookie(session(s.owner().token()))).andExpect(status().isNotFound());
	}

	@Test
	void theKeyOfAnotherWorkspaceNeverMakesALineExistingNorBlocksIt() throws Exception {
		ImportScenario mine = scenario();
		ImportScenario theirs = scenario();
		confirm(mvc, theirs.owner(), theirs.workspace(), theirs.checking(), body(line("id:1", "2026-11-05", "Deles", -100, null)))
				.andExpect(status().isCreated());

		// a mesma chave e a mesma linha em outra conta de outro workspace: nada de "já existe" e importa normalmente
		preview(mvc, mine.owner(), mine.workspace(), mine.checking(), "banco.ofx").andExpect(jsonPath("$.existing").value(0));
		confirm(mvc, mine.owner(), mine.workspace(), mine.checking(), body(line("id:1", "2026-11-05", "Minha", -100, null)))
				.andExpect(status().isCreated());

		assertThat(count(owner, theirs.checking())).isEqualTo(1);
		assertThat(count(owner, mine.checking())).isEqualTo(1);
	}

}
