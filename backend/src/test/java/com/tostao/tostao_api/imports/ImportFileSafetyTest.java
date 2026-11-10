package com.tostao.tostao_api.imports;

import static com.tostao.tostao_api.imports.ImportScenario.body;
import static com.tostao.tostao_api.imports.ImportScenario.confirm;
import static com.tostao.tostao_api.imports.ImportScenario.line;
import static com.tostao.tostao_api.imports.ImportScenario.preview;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * O arquivo é só dado (FR-022, FR-023, SC-011, SC-014): entidade externa e expansão de entidade não buscam nem expandem
 * nada, um PDF com ação e link não executa nem abre nada, entradas hostis terminam no tempo, nada do conteúdo vai para a
 * saída do processo e a escala do projeto responde dentro do orçamento.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ImportFileSafetyTest {

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

	// ---- entidades e links -------------------------------------------------------------------------------------

	@Test
	void anExternalEntityIsNeverFetchedNeitherFromTheNetworkNorFromTheDisk() throws Exception {
		ImportScenario s = scenario();
		try (ServerSocket watcher = new ServerSocket(0)) {
			watcher.setSoTimeout(1500);
			String ofx = """
					<?xml version="1.0"?>
					<!DOCTYPE OFX [
					  <!ENTITY remoto SYSTEM "http://127.0.0.1:%d/segredo">
					  <!ENTITY local SYSTEM "file:///etc/passwd">
					  <!ENTITY param "x">
					]>
					<OFX><STMTTRN><DTPOSTED>20261106</DTPOSTED><TRNAMT>-10.00</TRNAMT><FITID>r1</FITID><MEMO>&remoto;</MEMO></STMTTRN>
					<STMTTRN><DTPOSTED>20261106</DTPOSTED><TRNAMT>-11.00</TRNAMT><FITID>r2</FITID><MEMO>&local;</MEMO></STMTTRN></OFX>
					""".formatted(watcher.getLocalPort());

			String response = json(preview(mvc, s.owner(), s.workspace(), s.checking(), bytes(ofx)).andExpect(status().isOk()));

			assertThat((List<String>) JsonPath.read(response, "$.lines[*].description")).containsExactly("&remoto;", "&local;");
			assertThat(response).doesNotContain("root:").doesNotContain("/bin/");
			org.junit.jupiter.api.Assertions.assertThrows(SocketTimeoutException.class, watcher::accept);
		}
	}

	@Test
	void theDoctypeFileOfTestsExpandsNothing() throws Exception {
		ImportScenario s = scenario();

		long start = System.nanoTime();
		String response = json(preview(mvc, s.owner(), s.workspace(), s.checking(), "entidade.ofx").andExpect(status().isOk()));

		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));
		// a expansão aninhada (dez a dez a dez) teria 40.000 caracteres; aqui a referência fica como texto
		assertThat(response.length()).isLessThan(5_000);
		assertThat((List<String>) JsonPath.read(response, "$.lines[*].description")).containsExactly("&xxe;", "&remoto;", "&d;");
	}

	@Test
	void aPdfWithAScriptAnOpenActionAndALinkOpensNothing() throws Exception {
		ImportScenario s = scenario();
		try (ServerSocket watcher = new ServerSocket(0)) {
			watcher.setSoTimeout(1500);
			byte[] pdf = Pdfs.withLinkTo("http://127.0.0.1:" + watcher.getLocalPort() + "/abrir", "05/11/2026 Compra 1,00 D");

			preview(mvc, s.owner(), s.workspace(), s.checking(), pdf).andExpect(status().isOk())
					.andExpect(jsonPath("$.format").value("PDF")).andExpect(jsonPath("$.total").value(1));

			org.junit.jupiter.api.Assertions.assertThrows(SocketTimeoutException.class, watcher::accept);
		}
	}

	// ---- entradas hostis ---------------------------------------------------------------------------------------

	@Test
	void hostileTwoMegabyteInputsFinishQuicklyWithoutBreakingTheApi() throws Exception {
		ImportScenario s = scenario();
		int size = StatementParser.MAX_BYTES - 64;
		Map<String, byte[]> inputs = Map.ofEntries(
				Map.entry("abre-marca-sem-fechar", bytes("<OFX>" + "<".repeat(size))),
				Map.entry("muitas-marcas-vazias", bytes("<OFX>" + "<a>".repeat(size / 3))),
				Map.entry("muitos-movimentos-abertos", bytes("<OFX>" + "<STMTTRN>".repeat(size / 9))),
				Map.entry("aspas-sem-fechar", bytes("Data;Descrição;Valor\n\"" + "x".repeat(size))),
				Map.entry("so-separadores", bytes("Data;Descrição;Valor\n" + ";".repeat(size))),
				Map.entry("so-aspas", bytes("Data;Descrição;Valor\n" + "\"".repeat(size))),
				Map.entry("linhas-vazias", bytes("Data;Descrição;Valor\n" + "\n".repeat(size))),
				Map.entry("entidades-em-lote", bytes("<OFX><STMTTRN><MEMO>" + "&amp;".repeat(size / 5) + "</STMTTRN></OFX>")),
				Map.entry("numericas-em-lote", bytes("<OFX><STMTTRN><MEMO>" + "&#65;".repeat(size / 5) + "</STMTTRN></OFX>")),
				Map.entry("e-comerciais-soltos", bytes("<OFX><STMTTRN><MEMO>" + "&".repeat(size) + "</STMTTRN></OFX>")),
				Map.entry("e-comerciais-com-ponto-e-virgula-longe", bytes("<OFX><STMTTRN><MEMO>" + "&".repeat(size / 2) + ";".repeat(size / 2) + "</STMTTRN></OFX>")),
				Map.entry("pdf-lixo", ("%PDF-1.7\n" + "stream\n".repeat(size / 7)).getBytes(StandardCharsets.ISO_8859_1)));

		for (Map.Entry<String, byte[]> input : inputs.entrySet()) {
			long start = System.nanoTime();
			int status = preview(mvc, s.owner(), s.workspace(), s.checking(), input.getValue()).andReturn().getResponse().getStatus();

			assertThat(Duration.ofNanos(System.nanoTime() - start)).as(input.getKey()).isLessThan(Duration.ofSeconds(10));
			assertThat(status).as(input.getKey()).isIn(200, 400);
		}
		preview(mvc, s.owner(), s.workspace(), s.checking(), "banco.ofx").andExpect(status().isOk());
	}

	@Test
	void aFileWithAThousandLinesWithHugeDescriptionsIsCutNotExpanded() throws Exception {
		ImportScenario s = scenario();
		StringBuilder csv = new StringBuilder("Data;Descrição;Valor\n");
		for (int i = 0; i < 1000; i++) {
			csv.append("05/11/2026;").append(("d" + i + " ").repeat(150)).append(";-1,00\n");
		}

		String response = json(preview(mvc, s.owner(), s.workspace(), s.checking(), bytes(csv.toString())).andExpect(status().isOk()));

		List<String> descriptions = JsonPath.read(response, "$.lines[*].description");
		assertThat(descriptions).hasSize(1000).allSatisfy(description -> assertThat(description.length()).isLessThanOrEqualTo(200));
	}

	// ---- nada do arquivo vai para o registro -------------------------------------------------------------------

	@Test
	void noDescriptionAmountOrKeyOfTheFileReachesTheProcessOutput(CapturedOutput output) throws Exception {
		ImportScenario s = scenario();
		String secret = "SEGREDO-DO-EXTRATO-9137";
		// a saída acumula a da classe inteira (os outros testes imprimem): só o que for escrito daqui em diante conta
		int already = output.getAll().length();
		String csv = "Data;Descrição;Valor\n05/11/2026;" + secret + " um;-7771,23\n06/11/2026;" + secret + " dois;abc\n";
		String ofx = "<OFX><STMTTRN><DTPOSTED>20261106<TRNAMT>-4442.55<FITID>" + secret + "-fitid<MEMO>" + secret + " ofx</STMTTRN></OFX>";
		byte[] pdf = Pdfs.of("05/11/2026 " + secret + " pdf 3.331,44 D");

		for (byte[] file : new byte[][] { bytes(csv), bytes(ofx), pdf, Pdfs.encrypted("05/11/2026 " + secret + " 1,00 D"),
				bytes(secret + "\n" + secret) }) {
			preview(mvc, s.owner(), s.workspace(), s.checking(), file);
		}
		confirm(mvc, s.owner(), s.workspace(), s.checking(), body(line("chave-" + secret, "2026-11-05", secret + " confirmada", -777123, null)));
		confirm(mvc, s.owner(), s.workspace(), s.checking(), body(line("chave-" + secret, "1999-01-01", secret + " invalida", -777123, null)));
		confirm(mvc, s.owner(), s.workspace(), s.checking(), body(line("chave-" + secret, "2026-11-05", secret + " repetida", -777123, null)));

		String written = output.getAll().substring(already);
		assertThat(written).doesNotContain(secret).doesNotContain("7771,23").doesNotContain("777123")
				.doesNotContain("4442.55").doesNotContain("444255").doesNotContain("3.331,44").doesNotContain("333144");
	}

	// ---- escala ------------------------------------------------------------------------------------------------

	@Test
	void aThousandMovementsAreProcessedWithinTheBudgetWithTwentyThousandTransactionsInTheWorkspace() throws Exception {
		ImportScenario s = scenario();
		owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on, description, created_by)
				select :w, :a, :c, 'EXPENSE', -(g % 5000 + 1), date '2026-01-01' + (g % 300), 'Compra ' || (g % 700), :u
				from generate_series(1, 20000) g""")
				.param("w", s.workspace()).param("a", s.checking()).param("c", s.food()).param("u", s.owner().userId()).update();
		// aquecimento: o custo de primeira execução da JVM (classes, validador, serializadores) não é o que o SC-014 mede;
		// vai para outra conta, para não mexer na que será medida
		String warm = "Data;Descrição;Valor\n05/11/2026;Aquecimento;-1,00\n";
		String warmKey = JsonPath.read(json(preview(mvc, s.owner(), s.workspace(), s.savings(), bytes(warm)).andExpect(status().isOk())), "$.lines[0].key");
		confirm(mvc, s.owner(), s.workspace(), s.savings(), body(line(warmKey, "2026-11-05", "Aquecimento", -100, null))).andExpect(status().isCreated());

		// três lotes de 1.000 movimentos diferentes; vale a mediana, que não se deixa levar por uma pausa de GC ou de disco
		List<Duration> previewTimes = new ArrayList<>();
		List<Duration> confirmTimes = new ArrayList<>();
		for (int batch = 0; batch < 3; batch++) {
			StringBuilder csv = new StringBuilder("Data;Descrição;Valor\n");
			for (int i = 0; i < 1000; i++) {
				csv.append("%02d/11/2026;Compra %d lote %d;-%d,%02d\n".formatted((i % 28) + 1, i % 900, batch, (i % 90) + 1, i % 100));
			}
			long previewStart = System.nanoTime();
			String preview = json(preview(mvc, s.owner(), s.workspace(), s.checking(), bytes(csv.toString())).andExpect(status().isOk()));
			previewTimes.add(Duration.ofNanos(System.nanoTime() - previewStart));

			List<String> keys = JsonPath.read(preview, "$.lines[*].key");
			List<String> descriptions = JsonPath.read(preview, "$.lines[*].description");
			List<String> dates = JsonPath.read(preview, "$.lines[*].occurredOn");
			List<Integer> cents = JsonPath.read(preview, "$.lines[*].amountCents");
			List<String> lines = new ArrayList<>();
			for (int i = 0; i < keys.size(); i++) {
				lines.add(line(keys.get(i), dates.get(i), descriptions.get(i), cents.get(i), null));
			}
			long confirmStart = System.nanoTime();
			confirm(mvc, s.owner(), s.workspace(), s.checking(), body(lines.toArray(String[]::new))).andExpect(status().isCreated())
					.andExpect(jsonPath("$.created.length()").value(1000));
			confirmTimes.add(Duration.ofNanos(System.nanoTime() - confirmStart));
		}
		Duration previewTime = previewTimes.stream().sorted().toList().get(1);
		Duration confirmTime = confirmTimes.stream().sorted().toList().get(1);

		System.out.println("[import] prévia de 1.000 movimentos (mediana de 3): " + previewTime.toMillis() + " ms " + previewTimes
				+ "; confirmação: " + confirmTime.toMillis() + " ms " + confirmTimes);
		assertThat(previewTime).isLessThan(Duration.ofSeconds(2));
		assertThat(confirmTime).isLessThan(Duration.ofSeconds(3));
	}

}
