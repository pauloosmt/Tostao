package com.tostao.tostao_api.imports;

import static com.tostao.tostao_api.imports.ImportScenario.body;
import static com.tostao.tostao_api.imports.ImportScenario.confirm;
import static com.tostao.tostao_api.imports.ImportScenario.count;
import static com.tostao.tostao_api.imports.ImportScenario.fingerprint;
import static com.tostao.tostao_api.imports.ImportScenario.line;
import static com.tostao.tostao_api.imports.ImportScenario.preview;
import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * Importar de novo nunca duplica nem recria o que foi excluído (FR-015 a FR-017, SC-003, SC-004): confirmações repetidas,
 * extratos que se sobrepõem, exclusão respeitada, linhas idênticas do mesmo arquivo e confirmações simultâneas.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ImportIdempotencyTest {

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

	private String fromPreview(ImportScenario s, UUID account, String file) throws Exception {
		List<Map<String, Object>> lines = JsonPath.read(json(preview(mvc, s.owner(), s.workspace(), account, file)), "$.lines");
		List<String> out = new ArrayList<>();
		for (Map<String, Object> l : lines) {
			out.add(line((String) l.get("key"), (String) l.get("occurredOn"), (String) l.get("description"),
					((Number) l.get("amountCents")).longValue(), null));
		}
		return body(out.toArray(String[]::new));
	}

	private static String[] lines(int from, int to) {
		List<String> out = new ArrayList<>();
		for (int i = from; i < to; i++) {
			out.add(line("k" + i, "2026-11-05", "Compra " + i, -(i + 1), null));
		}
		return out.toArray(String[]::new);
	}

	// ---- repetir -----------------------------------------------------------------------------------------------

	@Test
	void confirmingTheSameFileTwiceCreatesNothingTheSecondTime() throws Exception {
		ImportScenario s = scenario();
		String request = fromPreview(s, s.checking(), "banco.ofx");

		confirm(mvc, s.owner(), s.workspace(), s.checking(), request).andExpect(status().isCreated()).andExpect(jsonPath("$.skipped").value(0));
		String before = fingerprint(owner, s.workspace());
		confirm(mvc, s.owner(), s.workspace(), s.checking(), request).andExpect(status().isOk())
				.andExpect(jsonPath("$.created.length()").value(0)).andExpect(jsonPath("$.skipped").value(4));

		assertThat(fingerprint(owner, s.workspace())).isEqualTo(before);
		assertThat(count(owner, s.checking())).isEqualTo(4);
	}

	@Test
	void theSecondPreviewMarksEverythingAsExisting() throws Exception {
		ImportScenario s = scenario();
		confirm(mvc, s.owner(), s.workspace(), s.checking(), fromPreview(s, s.checking(), "banco.ofx")).andExpect(status().isCreated());

		preview(mvc, s.owner(), s.workspace(), s.checking(), "banco.ofx").andExpect(status().isOk())
				.andExpect(jsonPath("$.existing").value(4)).andExpect(jsonPath("$.valid").value(0))
				.andExpect(jsonPath("$.lines[0].existing").value(true));
	}

	@Test
	void anOverlappingStatementCreatesOnlyTheNewMovements() throws Exception {
		ImportScenario s = scenario();

		confirm(mvc, s.owner(), s.workspace(), s.checking(), body(lines(0, 3))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.created.length()").value(3));
		confirm(mvc, s.owner(), s.workspace(), s.checking(), body(lines(1, 6))).andExpect(status().isCreated())
				.andExpect(jsonPath("$.created.length()").value(3)).andExpect(jsonPath("$.skipped").value(2));

		assertThat(count(owner, s.checking())).isEqualTo(6);
	}

	@Test
	void theSameKeysInAnotherAccountAreIndependent() throws Exception {
		ImportScenario s = scenario();
		String request = body(lines(0, 3));

		confirm(mvc, s.owner(), s.workspace(), s.checking(), request).andExpect(status().isCreated());
		confirm(mvc, s.owner(), s.workspace(), s.savings(), request).andExpect(status().isCreated())
				.andExpect(jsonPath("$.created.length()").value(3)).andExpect(jsonPath("$.skipped").value(0));

		assertThat(count(owner, s.checking())).isEqualTo(3);
		assertThat(count(owner, s.savings())).isEqualTo(3);
	}

	@Test
	void theSameKeysInAnotherWorkspaceAreIndependent() throws Exception {
		ImportScenario a = scenario();
		ImportScenario b = scenario();
		String request = body(lines(0, 3));

		confirm(mvc, a.owner(), a.workspace(), a.checking(), request).andExpect(status().isCreated());
		confirm(mvc, b.owner(), b.workspace(), b.checking(), request).andExpect(status().isCreated())
				.andExpect(jsonPath("$.created.length()").value(3));
	}

	// ---- excluídos e editados ----------------------------------------------------------------------------------

	@Test
	void anImportedTransactionThatWasDeletedDoesNotComeBack() throws Exception {
		ImportScenario s = scenario();
		String request = fromPreview(s, s.checking(), "banco.ofx");
		String created = json(confirm(mvc, s.owner(), s.workspace(), s.checking(), request).andExpect(status().isCreated()));
		String firstId = JsonPath.read(created, "$.created[1].id");

		mvc.perform(delete("/api/v1/workspaces/" + s.workspace() + "/transactions/" + firstId).cookie(session(s.owner().token())))
				.andExpect(status().isNoContent());
		confirm(mvc, s.owner(), s.workspace(), s.checking(), request).andExpect(status().isOk())
				.andExpect(jsonPath("$.created.length()").value(0)).andExpect(jsonPath("$.skipped").value(4));

		assertThat(owner.client().sql("select count(*) from transactions where account_id = :a and deleted_at is null")
				.param("a", s.checking()).query(Long.class).single()).isEqualTo(3L);
		// e a prévia os mostra como existentes, inclusive o excluído
		preview(mvc, s.owner(), s.workspace(), s.checking(), "banco.ofx").andExpect(jsonPath("$.existing").value(4));
	}

	@Test
	void editingAnImportedTransactionKeepsItsKeyAndTheImportStaysIdempotent() throws Exception {
		ImportScenario s = scenario();
		String request = fromPreview(s, s.checking(), "banco.ofx");
		String created = json(confirm(mvc, s.owner(), s.workspace(), s.checking(), request).andExpect(status().isCreated()));
		String id = JsonPath.read(created, "$.created[1].id");

		mvc.perform(put("/api/v1/workspaces/" + s.workspace() + "/transactions/" + id).cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"accountId\":\"%s\",\"amountCents\":3000,\"occurredOn\":\"2026-11-09\",\"description\":\"Editada\",\"categoryId\":\"%s\"}"
						.formatted(s.checking(), s.food())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.imported").value(true)).andExpect(jsonPath("$.categoryId").value(s.food().toString()));

		assertThat(owner.client().sql("select import_key from transactions where id = cast(:id as uuid)").param("id", id)
				.query(String.class).single()).isEqualTo("id:2");
		confirm(mvc, s.owner(), s.workspace(), s.checking(), request).andExpect(status().isOk()).andExpect(jsonPath("$.skipped").value(4));
	}

	// ---- linhas idênticas --------------------------------------------------------------------------------------

	@Test
	void twoIdenticalLinesOfTheSameFileBecomeTwoTransactionsAndStayTwo() throws Exception {
		ImportScenario s = scenario();
		String request = fromPreview(s, s.checking(), "banco.csv");

		confirm(mvc, s.owner(), s.workspace(), s.checking(), request).andExpect(status().isCreated())
				.andExpect(jsonPath("$.created.length()").value(4));
		confirm(mvc, s.owner(), s.workspace(), s.checking(), request).andExpect(status().isOk())
				.andExpect(jsonPath("$.created.length()").value(0)).andExpect(jsonPath("$.skipped").value(4));

		assertThat(owner.client().sql("select count(*) from transactions where account_id = :a and description = 'PADARIA DO ZE'")
				.param("a", s.checking()).query(Long.class).single()).isEqualTo(2L);
	}

	@Test
	void aLaterStatementRepeatingSomeDaysKeepsTheIdenticalLinesInOrder() throws Exception {
		ImportScenario s = scenario();
		String first = "Data;Descrição;Valor\n05/11/2026;Padaria;-10,00\n05/11/2026;Padaria;-10,00\n06/11/2026;Mercado;-50,00\n";
		String second = first + "07/11/2026;Farmácia;-30,00\n";

		for (String csv : new String[] { first, second }) {
			String request = fromPreviewBytes(s, csv);
			confirm(mvc, s.owner(), s.workspace(), s.checking(), request);
		}

		assertThat(count(owner, s.checking())).isEqualTo(4);
	}

	private String fromPreviewBytes(ImportScenario s, String csv) throws Exception {
		List<Map<String, Object>> lines = JsonPath.read(json(preview(mvc, s.owner(), s.workspace(), s.checking(),
				csv.getBytes(java.nio.charset.StandardCharsets.UTF_8))), "$.lines");
		List<String> out = new ArrayList<>();
		for (Map<String, Object> l : lines) {
			out.add(line((String) l.get("key"), (String) l.get("occurredOn"), (String) l.get("description"),
					((Number) l.get("amountCents")).longValue(), null));
		}
		return body(out.toArray(String[]::new));
	}

	// ---- falha e simultaneidade --------------------------------------------------------------------------------

	@Test
	void aBadLineInARepeatedConfirmationStillRefusesEverythingNew() throws Exception {
		ImportScenario s = scenario();
		confirm(mvc, s.owner(), s.workspace(), s.checking(), body(lines(0, 2))).andExpect(status().isCreated());
		String before = fingerprint(owner, s.workspace());

		confirm(mvc, s.owner(), s.workspace(), s.checking(), body(lines(0, 2)[0], lines(2, 3)[0], line("zero", "2026-11-05", "Zero", 0, null)))
				.andExpect(status().isBadRequest());

		assertThat(fingerprint(owner, s.workspace())).isEqualTo(before);
	}

	@Test
	void simultaneousConfirmationsOfTheSameLinesInDifferentOrdersCreateEachOnceWithoutDeadlock() throws Exception {
		ImportScenario s = scenario();
		int threads = 8;
		int size = 120;
		List<String> all = new ArrayList<>(List.of(lines(0, size)));
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				List<String> shuffled = new ArrayList<>(all);
				Collections.shuffle(shuffled, new java.util.Random(t));
				String request = body(shuffled.toArray(String[]::new));
				Callable<Integer> task = () -> {
					go.await();
					String response = json(confirm(mvc, s.owner(), s.workspace(), s.checking(), request));
					return ((List<?>) JsonPath.read(response, "$.created")).size();
				};
				results.add(pool.submit(task));
			}
			go.countDown();
			int created = 0;
			for (Future<Integer> result : results) {
				created += result.get(60, TimeUnit.SECONDS);
			}

			assertThat(created).as("cada linha foi criada por exatamente uma confirmação").isEqualTo(size);
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(count(owner, s.checking())).isEqualTo(size);
		assertThat(owner.client().sql("select count(distinct import_key) from transactions where account_id = :a")
				.param("a", s.checking()).query(Long.class).single()).isEqualTo((long) size);
	}

	@Test
	void simultaneousConfirmationsOfOverlappingLinesNeverDuplicate() throws Exception {
		ImportScenario s = scenario();
		ExecutorService pool = Executors.newFixedThreadPool(4);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int t = 0; t < 4; t++) {
				String request = body(lines(t * 10, t * 10 + 40));
				results.add(pool.submit(() -> {
					go.await();
					return ((List<?>) JsonPath.read(json(confirm(mvc, s.owner(), s.workspace(), s.checking(), request)), "$.created")).size();
				}));
			}
			go.countDown();
			int created = 0;
			for (Future<Integer> result : results) {
				created += result.get(60, TimeUnit.SECONDS);
			}

			// de k0 a k69, cada uma uma só vez
			assertThat(created).isEqualTo(70);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(count(owner, s.checking())).isEqualTo(70);
	}

}
