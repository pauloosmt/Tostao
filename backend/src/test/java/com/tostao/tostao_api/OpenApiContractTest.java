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
	private static final Path DASHBOARD_CONTRACT = Path.of("..", "specs", "005-dashboard-mensal", "contracts",
			"openapi.yaml");
	private static final Path BUDGETS_CONTRACT = Path.of("..", "specs", "006-orcamento-semanal", "contracts",
			"openapi.yaml");
	private static final Path MONTHLY_BUDGETS_CONTRACT = Path.of("..", "specs", "007-orcamento-mensal", "contracts",
			"openapi.yaml");
	private static final Path INVOICES_CONTRACT = Path.of("..", "specs", "008-fatura-cartao", "contracts",
			"openapi.yaml");
	private static final Pattern PATH_LINE =Pattern.compile("^  (/[^:\\s]+):\\s*$");
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
	void dashboardContractFileDeclaresOnlyTheReadRoute() throws IOException {
		Map<String, List<String>> operations = contractOperations(DASHBOARD_CONTRACT);

		assertThat(operations).containsOnlyKeys("/api/v1/workspaces/{wid}/dashboard");
		assertThat(operations.get("/api/v1/workspaces/{wid}/dashboard")).containsExactly("get");
	}

	@Test
	void budgetsContractFileDeclaresTheExpectedRoutes() throws IOException {
		Map<String, List<String>> operations = contractOperations(BUDGETS_CONTRACT);

		assertThat(operations).containsOnlyKeys(
				"/api/v1/workspaces/{wid}/budgets",
				"/api/v1/workspaces/{wid}/budgets/{id}",
				"/api/v1/workspaces/{wid}/budgets/copy");
		assertThat(operations.get("/api/v1/workspaces/{wid}/budgets")).containsExactlyInAnyOrder("get", "put");
		assertThat(operations.get("/api/v1/workspaces/{wid}/budgets/{id}")).containsExactlyInAnyOrder("put", "delete");
		assertThat(operations.get("/api/v1/workspaces/{wid}/budgets/copy")).containsExactly("post");
	}

	@Test
	void theBudgetsExposeExactlyTheDeclaredOperationsAndNoPatch() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/budgets'].get").exists())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/budgets'].put").exists())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/budgets'].post").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/budgets'].delete").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/budgets'].patch").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/budgets/{id}'].put").exists())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/budgets/{id}'].delete").exists())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/budgets/{id}'].get").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/budgets/{id}'].patch").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/budgets/copy'].post").exists())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/budgets'].get.parameters[?(@.name=='week')]")
						.exists());
	}

	@Test
	void monthlyBudgetsContractFileDeclaresTheExpectedRoutes() throws IOException {
		Map<String, List<String>> operations = contractOperations(MONTHLY_BUDGETS_CONTRACT);

		assertThat(operations).containsOnlyKeys(
				"/api/v1/workspaces/{wid}/monthly-budgets",
				"/api/v1/workspaces/{wid}/monthly-budgets/{id}",
				"/api/v1/workspaces/{wid}/monthly-budgets/copy");
		assertThat(operations.get("/api/v1/workspaces/{wid}/monthly-budgets")).containsExactlyInAnyOrder("get", "put");
		assertThat(operations.get("/api/v1/workspaces/{wid}/monthly-budgets/{id}"))
				.containsExactlyInAnyOrder("put", "delete");
		assertThat(operations.get("/api/v1/workspaces/{wid}/monthly-budgets/copy")).containsExactly("post");
	}

	@Test
	void theMonthlyBudgetsExposeExactlyTheDeclaredOperationsAndNoPatch() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/monthly-budgets'].get").exists())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/monthly-budgets'].put").exists())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/monthly-budgets'].post").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/monthly-budgets'].delete").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/monthly-budgets'].patch").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/monthly-budgets/{id}'].put").exists())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/monthly-budgets/{id}'].delete").exists())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/monthly-budgets/{id}'].get").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/monthly-budgets/{id}'].patch").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/monthly-budgets/copy'].post").exists())
				.andExpect(jsonPath(
						"$.paths['/api/v1/workspaces/{wid}/monthly-budgets'].get.parameters[?(@.name=='month')]")
						.exists());
	}

	@Test
	void theMonthlyContractMatchesTheRequestAndResponseFields() throws Exception {
		String contract = Files.readString(MONTHLY_BUDGETS_CONTRACT);
		for (String field : List.of("month", "fromMonth", "months", "categoryId", "amountCents", "totalSpentCents",
				"spentOutsideCategoryLimitsCents", "budgets", "skippedExisting", "skippedArchivedCategory")) {
			assertThat(contract).as(field).contains(field + ":");
		}
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("$.components.schemas.DefineMonthlyBudgetRequest.properties.month").exists())
				.andExpect(jsonPath("$.components.schemas.DefineMonthlyBudgetRequest.properties.categoryId").exists())
				.andExpect(jsonPath("$.components.schemas.DefineMonthlyBudgetRequest.properties.amountCents").exists())
				.andExpect(jsonPath("$.components.schemas.CopyMonthlyBudgetsRequest.properties.fromMonth").exists())
				.andExpect(jsonPath("$.components.schemas.CopyMonthlyBudgetsRequest.properties.months").exists())
				.andExpect(jsonPath("$.components.schemas.MonthBudgetResponse.properties.month").exists())
				.andExpect(jsonPath("$.components.schemas.MonthBudgetResponse.properties.totalSpentCents").exists())
				.andExpect(jsonPath("$.components.schemas.MonthBudgetResponse.properties.budgets").exists())
				.andExpect(jsonPath("$.components.schemas.MonthlyBudgetResponse.properties.month").exists());
	}

	@Test
	void invoicesContractFileDeclaresOnlyReadRoutes() throws IOException {
		Map<String, List<String>> operations = contractOperations(INVOICES_CONTRACT);

		assertThat(operations).containsOnlyKeys(
				"/api/v1/workspaces/{wid}/accounts/{accountId}/invoices/{month}",
				"/api/v1/workspaces/{wid}/accounts/{accountId}/invoices",
				"/api/v1/workspaces/{wid}/accounts/{accountId}/card-summary");
		operations.values().forEach(methods -> assertThat(methods).containsExactly("get"));
	}

	@Test
	void theInvoicesAreReadOnlyInTheGeneratedDocs() throws Exception {
		String base = "$.paths['/api/v1/workspaces/{wid}/accounts/{accountId}";
		var docs = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
		for (String path : List.of("/invoices/{month}']", "/invoices']", "/card-summary']")) {
			docs.andExpect(jsonPath(base + path + ".get").exists())
					.andExpect(jsonPath(base + path + ".post").doesNotExist())
					.andExpect(jsonPath(base + path + ".put").doesNotExist())
					.andExpect(jsonPath(base + path + ".patch").doesNotExist())
					.andExpect(jsonPath(base + path + ".delete").doesNotExist());
		}
		docs.andExpect(jsonPath(base + "/invoices'].get.parameters[?(@.name=='from')]").exists())
				.andExpect(jsonPath(base + "/invoices'].get.parameters[?(@.name=='to')]").exists())
				.andExpect(jsonPath("$.components.schemas.InvoiceResponse.properties.lines").exists())
				.andExpect(jsonPath("$.components.schemas.InvoiceListItem.properties.lines").doesNotExist())
				.andExpect(jsonPath("$.components.schemas.CardSummaryResponse.properties.openInvoice").exists());
	}

	@Test
	void theDashboardIsReadOnlyAndAcceptsMonthOrWeek() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/dashboard'].get").exists())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/dashboard'].post").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/dashboard'].put").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/dashboard'].patch").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/dashboard'].delete").doesNotExist())
				.andExpect(jsonPath(
						"$.paths['/api/v1/workspaces/{wid}/dashboard'].get.parameters[?(@.name=='month')]").exists())
				.andExpect(jsonPath(
						"$.paths['/api/v1/workspaces/{wid}/dashboard'].get.parameters[?(@.name=='week')]").exists());
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
		for (Path contract : List.of(AUTH_CONTRACT, ACCOUNTS_CONTRACT, CATEGORIES_CONTRACT, TRANSACTIONS_CONTRACT,
				DASHBOARD_CONTRACT, BUDGETS_CONTRACT, MONTHLY_BUDGETS_CONTRACT, INVOICES_CONTRACT)) {
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
