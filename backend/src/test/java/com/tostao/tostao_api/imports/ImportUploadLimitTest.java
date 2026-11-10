package com.tostao.tostao_api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.AuthSupport;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/**
 * O limite de 2 MB do envio (FR-007) vale no servidor de verdade: o MockMvc não aplica os limites do multipart. Um arquivo
 * de exatamente 2 MB passa; um byte a mais é recusado com 413, e o corpo do erro não cita nada do arquivo.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ImportUploadLimitTest {

	@LocalServerPort
	int port;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private final HttpClient http = HttpClient.newHttpClient();

	private HttpResponse<String> upload(ImportScenario s, byte[] file) throws Exception {
		String boundary = "----tostao" + UUID.randomUUID();
		byte[] head = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"extrato.csv\"\r\n"
				+ "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1);
		byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
		byte[] body = new byte[head.length + file.length + tail.length];
		System.arraycopy(head, 0, body, 0, head.length);
		System.arraycopy(file, 0, body, head.length, file.length);
		System.arraycopy(tail, 0, body, head.length + file.length, tail.length);
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/workspaces/" + s.workspace()
				+ "/accounts/" + s.checking() + "/import/preview"))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.header("Cookie", AuthSupport.COOKIE + "=" + s.owner().token())
				.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/** Um CSV de uma linha, preenchido na descrição até ter exatamente {@code total} bytes. */
	private static byte[] csvOf(int total) {
		String head = "Data;Descrição;Valor\n05/11/2026;";
		String tail = ";-1,00\n";
		int fill = total - head.getBytes(StandardCharsets.UTF_8).length - tail.length();
		return (head + "x".repeat(fill) + tail).getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void aFileOfExactlyTwoMegabytesPassesAndOneByteMoreIs413() throws Exception {
		ImportScenario s = ImportScenario.create(new WorkspaceFixtures(owner, tokens));

		HttpResponse<String> exact = upload(s, csvOf(StatementParser.MAX_BYTES));
		HttpResponse<String> over = upload(s, csvOf(StatementParser.MAX_BYTES + 1));

		assertThat(exact.statusCode()).isEqualTo(200);
		assertThat((String) JsonPath.read(exact.body(), "$.format")).isEqualTo("CSV");
		assertThat(over.statusCode()).isEqualTo(413);
		assertThat((String) JsonPath.read(over.body(), "$.title")).isEqualTo("Arquivo grande demais");
		assertThat(over.body()).doesNotContain("xxxxxxxxxx");
	}

	@Test
	void aMuchBiggerFileIsAlsoRefusedWith413() throws Exception {
		ImportScenario s = ImportScenario.create(new WorkspaceFixtures(owner, tokens));

		// o servidor recusa e fecha a conexão: o cliente vê o 413 ou o fechamento, nunca uma prévia
		try {
			assertThat(upload(s, new byte[4 * 1024 * 1024]).statusCode()).isEqualTo(413);
		}
		catch (java.io.IOException closedWhileSending) {
			assertThat(closedWhileSending).isNotNull();
		}
	}

	@Test
	void aRequestWithoutTheFilePartIs400PointingAtTheFile() throws Exception {
		ImportScenario s = ImportScenario.create(new WorkspaceFixtures(owner, tokens));
		String boundary = "----tostao" + UUID.randomUUID();
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/workspaces/" + s.workspace()
				+ "/accounts/" + s.checking() + "/import/preview"))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.header("Cookie", AuthSupport.COOKIE + "=" + s.owner().token())
				.POST(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n")).build();

		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat((String) JsonPath.read(response.body(), "$.errors[0].field")).isEqualTo("file");
	}

}
