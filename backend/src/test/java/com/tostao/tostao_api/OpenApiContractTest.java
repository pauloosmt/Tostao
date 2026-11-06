package com.tostao.tostao_api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * As rotas que a API realmente expõe batem com o contrato desenhado na spec
 * ({@code specs/001-autenticacao-workspace/contracts/openapi.yaml}).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true" })
class OpenApiContractTest {

	private static final Path AUTH_CONTRACT = Path.of("..", "specs", "001-autenticacao-workspace", "contracts",
			"openapi.yaml");
	private static final Path ACCOUNTS_CONTRACT = Path.of("..", "specs", "002-contas", "contracts", "openapi.yaml");
	private static final Path CATEGORIES_CONTRACT = Path.of("..", "specs", "003-categorias", "contracts",
			"openapi.yaml");
	private static final Path TRANSACTIONS_CONTRACT = Path.of("..", "specs", "004-lancamentos", "contracts",
			"openapi.yaml");
	private static final Pattern PATH_LINE = Pattern.compile("^  (/[^:\\s]+):\\s*$");
	private static final Pattern METHOD_LINE = Pattern.compile("^    (get|post|put|delete):\\s*$");

	@Autowired
	MockMvc mvc;

	/** Caminho (com o prefixo do servidor) para os métodos declarados no contrato. */
	private static Map<String, List<String>> contractOperations(Path contract) throws IOException {
		Map<String, List<String>> operations = new LinkedHashMap<>();
		String current = null;
		boolean inPaths = false;
		for (String line : Files.readAllLines(contract)) {
			if (line.equals("paths:")) {
				inPaths = true;
				continue;
			}
			if (inPaths && !line.isBlank() && !line.startsWith(" ")) {
				break;
			}
			if (!inPaths) {
				continue;
			}
			Matcher path = PATH_LINE.matcher(line);
			if (path.matches()) {
				current = "/api/v1" + path.group(1);
				operations.put(current, new ArrayList<>());
				continue;
			}
			Matcher method = METHOD_LINE.matcher(line);
			if (method.matches() && current != null) {
				operations.get(current).add(method.group(1));
			}
		}
		return operations;
	}

	@Test
	void authContractFileDeclaresTheExpectedRoutes() throws IOException {
		assertThat(contractOperations(AUTH_CONTRACT)).containsOnlyKeys("/api/v1/auth/register", "/api/v1/auth/login",
				"/api/v1/auth/logout", "/api/v1/auth/logout-all", "/api/v1/me", "/api/v1/workspaces/{wid}");
	}

	@Test
	void accountsContractFileDeclaresTheExpectedRoutes() throws IOException {
		assertThat(contractOperations(ACCOUNTS_CONTRACT)).containsOnlyKeys(
				"/api/v1/workspaces/{wid}/accounts",
				"/api/v1/workspaces/{wid}/accounts/{id}",
				"/api/v1/workspaces/{wid}/accounts/{id}/archive",
				"/api/v1/workspaces/{wid}/accounts/{id}/restore",
				"/api/v1/workspaces/{wid}/reports/balance");
	}

	@Test
	void categoriesContractFileDeclaresTheExpectedRoutes() throws IOException {
		assertThat(contractOperations(CATEGORIES_CONTRACT)).containsOnlyKeys(
				"/api/v1/workspaces/{wid}/categories",
				"/api/v1/workspaces/{wid}/categories/{id}",
				"/api/v1/workspaces/{wid}/categories/{id}/archive",
				"/api/v1/workspaces/{wid}/categories/{id}/restore");
	}

	@Test
	void transactionsContractFileDeclaresTheExpectedRoutes() throws IOException {
		Map<String, List<String>> operations = contractOperations(TRANSACTIONS_CONTRACT);

		assertThat(operations).containsOnlyKeys(
				"/api/v1/workspaces/{wid}/transactions",
				"/api/v1/workspaces/{wid}/transactions/{id}");
		assertThat(operations.get("/api/v1/workspaces/{wid}/transactions")).containsExactlyInAnyOrder("get", "post");
		assertThat(operations.get("/api/v1/workspaces/{wid}/transactions/{id}"))
				.containsExactlyInAnyOrder("get", "put", "delete");
	}

	@Test
	void transactionsAreDeletedOnlyLogicallyAndHaveNoRestoreRoute() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/transactions'].delete").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/transactions/{id}'].delete").exists())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/transactions/{id}/restore']").doesNotExist());
	}

	@Test
	void everyOperationInTheContractsExistsInTheGeneratedDocs() throws Exception {
		var docs = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
		for (Path contract : List.of(AUTH_CONTRACT, ACCOUNTS_CONTRACT, CATEGORIES_CONTRACT, TRANSACTIONS_CONTRACT)) {
			for (var entry : contractOperations(contract).entrySet()) {
				for (String method : entry.getValue()) {
					docs.andExpect(jsonPath("$.paths['" + entry.getKey() + "']." + method).exists());
				}
			}
		}
	}

	@Test
	void accountsCanNeverBeDeleted() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/accounts/{id}'].delete").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/accounts'].delete").doesNotExist());
	}

	@Test
	void categoriesCanNeverBeDeleted() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/categories/{id}'].delete").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/categories'].delete").doesNotExist());
	}

}
