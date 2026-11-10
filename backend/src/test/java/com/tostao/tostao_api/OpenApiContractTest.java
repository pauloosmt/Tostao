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
	private static final Path PENDING_CONTRACT = Path.of("..", "specs", "009-lancamento-pendente", "contracts",
			"openapi.yaml");
	private static final Path INSTALLMENTS_CONTRACT = Path.of("..", "specs", "010-compra-parcelada", "contracts",
			"openapi.yaml");
	private static final Path RECURRENCES_CONTRACT = Path.of("..", "specs", "011-recorrencias-agendamentos", "contracts",
			"openapi.yaml");
	private static final Path MEMBERSHIP_CONTRACT = Path.of("..", "specs", "012-membros-convites", "contracts",
			"openapi.yaml");
	private static final Path IMPORT_CONTRACT = Path.of("..", "specs", "013-importacao-extratos", "contracts",
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
	void pendingContractFileDeclaresTheClearRouteAndTheChangedOnes() throws IOException {
		Map<String, List<String>> operations = contractOperations(PENDING_CONTRACT);

		assertThat(operations).containsOnlyKeys(
				"/api/v1/workspaces/{wid}/transactions/{id}/clear",
				"/api/v1/workspaces/{wid}/transactions",
				"/api/v1/workspaces/{wid}/transactions/{id}");
		assertThat(operations.get("/api/v1/workspaces/{wid}/transactions/{id}/clear")).containsExactly("post");
		assertThat(operations.get("/api/v1/workspaces/{wid}/transactions")).containsExactlyInAnyOrder("get", "post");
		assertThat(operations.get("/api/v1/workspaces/{wid}/transactions/{id}")).containsExactly("put");
	}

	@Test
	void theClearRouteAndTheStatusFieldsAreInTheGeneratedDocs() throws Exception {
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/transactions/{id}/clear'].post").exists())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/transactions/{id}/clear'].get").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/transactions/{id}/clear'].put").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/transactions/{id}/clear'].delete").doesNotExist())
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/transactions'].get.parameters[?(@.name=='status')]").exists())
				.andExpect(jsonPath("$.components.schemas.TransactionResponse.properties.status").exists())
				.andExpect(jsonPath("$.components.schemas.CreateTransactionRequest.properties.status").exists())
				.andExpect(jsonPath("$.components.schemas.UpdateTransactionRequest.properties.status").exists())
				.andExpect(jsonPath("$.components.schemas.AccountResponse.properties.pendingCents").exists())
				.andExpect(jsonPath("$.components.schemas.AccountResponse.properties.projectedBalanceCents").exists())
				.andExpect(jsonPath("$.components.schemas.BalanceReportResponse.properties.totalProjectedCents").exists())
				.andExpect(jsonPath("$.components.schemas.DashboardResponse.properties.pending").exists());
	}

	@Test
	void installmentsContractFileDeclaresTheThreeRoutesAndTheTransactionList() throws IOException {
		Map<String, List<String>> operations = contractOperations(INSTALLMENTS_CONTRACT);

		assertThat(operations).containsOnlyKeys(
				"/api/v1/workspaces/{wid}/installment-purchases",
				"/api/v1/workspaces/{wid}/installment-purchases/{groupId}",
				"/api/v1/workspaces/{wid}/installment-purchases/{groupId}/cancel",
				"/api/v1/workspaces/{wid}/transactions");
		assertThat(operations.get("/api/v1/workspaces/{wid}/installment-purchases")).containsExactly("post");
		assertThat(operations.get("/api/v1/workspaces/{wid}/installment-purchases/{groupId}")).containsExactly("get");
		assertThat(operations.get("/api/v1/workspaces/{wid}/installment-purchases/{groupId}/cancel")).containsExactly("post");
		assertThat(operations.get("/api/v1/workspaces/{wid}/transactions")).containsExactly("get");
	}

	@Test
	void theInstallmentRoutesAndFieldsAreInTheGeneratedDocsAndNothingElse() throws Exception {
		String purchases = "$.paths['/api/v1/workspaces/{wid}/installment-purchases']";
		String plan = "$.paths['/api/v1/workspaces/{wid}/installment-purchases/{groupId}']";
		String cancel = "$.paths['/api/v1/workspaces/{wid}/installment-purchases/{groupId}/cancel']";
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				// o plano não tem como ser editado nem apagado: só registrar, consultar e cancelar
				.andExpect(jsonPath(purchases + ".post").exists())
				.andExpect(jsonPath(purchases + ".get").doesNotExist())
				.andExpect(jsonPath(purchases + ".put").doesNotExist())
				.andExpect(jsonPath(purchases + ".delete").doesNotExist())
				.andExpect(jsonPath(plan + ".get").exists())
				.andExpect(jsonPath(plan + ".post").doesNotExist())
				.andExpect(jsonPath(plan + ".put").doesNotExist())
				.andExpect(jsonPath(plan + ".patch").doesNotExist())
				.andExpect(jsonPath(plan + ".delete").doesNotExist())
				.andExpect(jsonPath(cancel + ".post").exists())
				.andExpect(jsonPath(cancel + ".get").doesNotExist())
				.andExpect(jsonPath(cancel + ".put").doesNotExist())
				.andExpect(jsonPath(cancel + ".delete").doesNotExist())
				// os campos de parcela de todo lançamento
				.andExpect(jsonPath("$.components.schemas.TransactionResponse.properties.installmentGroupId").exists())
				.andExpect(jsonPath("$.components.schemas.TransactionResponse.properties.installmentNumber").exists())
				.andExpect(jsonPath("$.components.schemas.TransactionResponse.properties.installmentCount").exists())
				// o plano
				.andExpect(jsonPath("$.components.schemas.InstallmentPlanResponse.properties.groupId").exists())
				.andExpect(jsonPath("$.components.schemas.InstallmentPlanResponse.properties.description").exists())
				.andExpect(jsonPath("$.components.schemas.InstallmentPlanResponse.properties.installmentCount").exists())
				.andExpect(jsonPath("$.components.schemas.InstallmentPlanResponse.properties.accountId").exists())
				.andExpect(jsonPath("$.components.schemas.InstallmentPlanResponse.properties.categoryId").exists())
				.andExpect(jsonPath("$.components.schemas.InstallmentPlanResponse.properties.totalCents").exists())
				.andExpect(jsonPath("$.components.schemas.InstallmentPlanResponse.properties.clearedCents").exists())
				.andExpect(jsonPath("$.components.schemas.InstallmentPlanResponse.properties.pendingCents").exists())
				.andExpect(jsonPath("$.components.schemas.InstallmentPlanResponse.properties.installments").exists())
				.andExpect(jsonPath("$.components.schemas.CancelInstallmentsResponse.properties.cancelled").exists())
				// o corpo do registro: só os campos permitidos, e nada de grupo, número ou autor
				.andExpect(jsonPath("$.components.schemas.CreateInstallmentPurchaseRequest.properties.accountId").exists())
				.andExpect(jsonPath("$.components.schemas.CreateInstallmentPurchaseRequest.properties.categoryId").exists())
				.andExpect(jsonPath("$.components.schemas.CreateInstallmentPurchaseRequest.properties.totalCents").exists())
				.andExpect(jsonPath("$.components.schemas.CreateInstallmentPurchaseRequest.properties.installmentCount").exists())
				.andExpect(jsonPath("$.components.schemas.CreateInstallmentPurchaseRequest.properties.firstOccurredOn").exists())
				.andExpect(jsonPath("$.components.schemas.CreateInstallmentPurchaseRequest.properties.description").exists())
				.andExpect(jsonPath("$.components.schemas.CreateInstallmentPurchaseRequest.properties.notes").exists())
				.andExpect(jsonPath("$.components.schemas.CreateInstallmentPurchaseRequest.properties.firstInstallmentStatus").exists())
				.andExpect(jsonPath("$.components.schemas.CreateInstallmentPurchaseRequest.properties.installmentGroupId").doesNotExist())
				.andExpect(jsonPath("$.components.schemas.CreateInstallmentPurchaseRequest.properties.installmentNumber").doesNotExist())
				.andExpect(jsonPath("$.components.schemas.CreateInstallmentPurchaseRequest.properties.createdBy").doesNotExist())
				.andExpect(jsonPath("$.components.schemas.CreateInstallmentPurchaseRequest.properties.workspaceId").doesNotExist());
	}

	@Test
	void recurrencesContractFileDeclaresTheFourRoutesAndTheChangedOnes() throws IOException {
		Map<String, List<String>> operations = contractOperations(RECURRENCES_CONTRACT);

		assertThat(operations).containsOnlyKeys(
				"/api/v1/workspaces/{wid}/recurrences",
				"/api/v1/workspaces/{wid}/recurrences/{id}",
				"/api/v1/workspaces/{wid}/recurrences/{id}/end",
				"/api/v1/workspaces/{wid}/transactions",
				"/api/v1/workspaces/{wid}/transactions/{id}",
				"/api/v1/workspaces/{wid}/installment-purchases");
		assertThat(operations.get("/api/v1/workspaces/{wid}/recurrences")).containsExactlyInAnyOrder("get", "post");
		assertThat(operations.get("/api/v1/workspaces/{wid}/recurrences/{id}")).containsExactly("get");
		assertThat(operations.get("/api/v1/workspaces/{wid}/recurrences/{id}/end")).containsExactly("post");
		assertThat(operations.get("/api/v1/workspaces/{wid}/transactions")).containsExactly("post");
		assertThat(operations.get("/api/v1/workspaces/{wid}/transactions/{id}")).containsExactly("put");
		assertThat(operations.get("/api/v1/workspaces/{wid}/installment-purchases")).containsExactly("post");
	}

	@Test
	void theRecurrenceRoutesAndFieldsAreInTheGeneratedDocsAndNothingElse() throws Exception {
		String recurrences = "$.paths['/api/v1/workspaces/{wid}/recurrences']";
		String one = "$.paths['/api/v1/workspaces/{wid}/recurrences/{id}']";
		String end = "$.paths['/api/v1/workspaces/{wid}/recurrences/{id}/end']";
		String schemas = "$.components.schemas.";
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				// a regra da recorrência não é editável nem se apaga: só criar, listar, consultar e encerrar
				.andExpect(jsonPath(recurrences + ".post").exists())
				.andExpect(jsonPath(recurrences + ".get").exists())
				.andExpect(jsonPath(recurrences + ".put").doesNotExist())
				.andExpect(jsonPath(recurrences + ".delete").doesNotExist())
				.andExpect(jsonPath(one + ".get").exists())
				.andExpect(jsonPath(one + ".post").doesNotExist())
				.andExpect(jsonPath(one + ".put").doesNotExist())
				.andExpect(jsonPath(one + ".patch").doesNotExist())
				.andExpect(jsonPath(one + ".delete").doesNotExist())
				.andExpect(jsonPath(end + ".post").exists())
				.andExpect(jsonPath(end + ".get").doesNotExist())
				.andExpect(jsonPath(end + ".put").doesNotExist())
				.andExpect(jsonPath(end + ".delete").doesNotExist())
				// o marcador de efetivação automática em lançamentos e na compra parcelada
				.andExpect(jsonPath(schemas + "CreateTransactionRequest.properties.autoClear").exists())
				.andExpect(jsonPath(schemas + "UpdateTransactionRequest.properties.autoClear").exists())
				.andExpect(jsonPath(schemas + "CreateInstallmentPurchaseRequest.properties.autoClear").exists())
				// os campos acrescentados a todo lançamento
				.andExpect(jsonPath(schemas + "TransactionResponse.properties.autoClear").exists())
				.andExpect(jsonPath(schemas + "TransactionResponse.properties.recurrenceId").exists())
				.andExpect(jsonPath(schemas + "TransactionResponse.properties.occurrenceNumber").exists())
				// o resumo e o detalhe da recorrência
				.andExpect(jsonPath(schemas + "RecurrenceResponse.properties.nextOccurrenceOn").exists())
				.andExpect(jsonPath(schemas + "RecurrenceResponse.properties.ended").exists())
				.andExpect(jsonPath(schemas + "RecurrenceResponse.properties.frequency").exists())
				.andExpect(jsonPath(schemas + "RecurrenceDetailResponse.properties.totalCents").exists())
				.andExpect(jsonPath(schemas + "RecurrenceDetailResponse.properties.clearedCents").exists())
				.andExpect(jsonPath(schemas + "RecurrenceDetailResponse.properties.pendingCents").exists())
				.andExpect(jsonPath(schemas + "RecurrenceDetailResponse.properties.occurrences").exists())
				.andExpect(jsonPath(schemas + "EndRecurrenceResponse.properties.cancelled").exists())
				// o corpo da criação: só os campos permitidos, e nada de autor, workspace, estado ou recorrência de origem
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.accountId").exists())
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.categoryId").exists())
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.type").exists())
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.amountCents").exists())
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.description").exists())
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.notes").exists())
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.frequency").exists())
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.startOn").exists())
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.endOn").exists())
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.autoClear").exists())
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.createdBy").doesNotExist())
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.workspaceId").doesNotExist())
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.recurrenceId").doesNotExist())
				.andExpect(jsonPath(schemas + "CreateRecurrenceRequest.properties.status").doesNotExist());
	}

	@Test
	void importContractFileDeclaresTheTwoRoutes() throws IOException {
		Map<String, List<String>> operations = contractOperations(IMPORT_CONTRACT);

		assertThat(operations).containsOnlyKeys(
				"/api/v1/workspaces/{wid}/accounts/{accountId}/import/preview",
				"/api/v1/workspaces/{wid}/accounts/{accountId}/import");
		assertThat(operations.get("/api/v1/workspaces/{wid}/accounts/{accountId}/import/preview")).containsExactly("post");
		assertThat(operations.get("/api/v1/workspaces/{wid}/accounts/{accountId}/import")).containsExactly("post");
	}

	@Test
	void theImportRoutesAndFieldsAreInTheGeneratedDocsAndNothingElse() throws Exception {
		String preview = "$.paths['/api/v1/workspaces/{wid}/accounts/{accountId}/import/preview']";
		String confirm = "$.paths['/api/v1/workspaces/{wid}/accounts/{accountId}/import']";
		String schemas = "$.components.schemas.";
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				// só POST: a prévia não grava e a confirmação é a única que grava; nada é consultado nem editado depois
				.andExpect(jsonPath(preview + ".post").exists())
				.andExpect(jsonPath(preview + ".get").doesNotExist())
				.andExpect(jsonPath(preview + ".put").doesNotExist())
				.andExpect(jsonPath(preview + ".delete").doesNotExist())
				.andExpect(jsonPath(confirm + ".post").exists())
				.andExpect(jsonPath(confirm + ".get").doesNotExist())
				.andExpect(jsonPath(confirm + ".put").doesNotExist())
				.andExpect(jsonPath(confirm + ".delete").doesNotExist())
				// o arquivo vai como multipart na parte "file"; a confirmação é JSON
				.andExpect(jsonPath(preview + ".post.requestBody.content['multipart/form-data']").exists())
				.andExpect(jsonPath(preview + ".post.requestBody.content['application/json']").doesNotExist())
				.andExpect(jsonPath(confirm + ".post.requestBody.content['application/json']").exists())
				// a prévia
				.andExpect(jsonPath(schemas + "ImportPreview.properties.format").exists())
				.andExpect(jsonPath(schemas + "ImportPreview.properties.total").exists())
				.andExpect(jsonPath(schemas + "ImportPreview.properties.valid").exists())
				.andExpect(jsonPath(schemas + "ImportPreview.properties.existing").exists())
				.andExpect(jsonPath(schemas + "ImportPreview.properties.invalid").exists())
				.andExpect(jsonPath(schemas + "ImportPreview.properties.lines").exists())
				.andExpect(jsonPath(schemas + "ImportLinePreview.properties.line").exists())
				.andExpect(jsonPath(schemas + "ImportLinePreview.properties.occurredOn").exists())
				.andExpect(jsonPath(schemas + "ImportLinePreview.properties.description").exists())
				.andExpect(jsonPath(schemas + "ImportLinePreview.properties.amountCents").exists())
				.andExpect(jsonPath(schemas + "ImportLinePreview.properties.type").exists())
				.andExpect(jsonPath(schemas + "ImportLinePreview.properties.key").exists())
				.andExpect(jsonPath(schemas + "ImportLinePreview.properties.existing").exists())
				.andExpect(jsonPath(schemas + "ImportLinePreview.properties.possibleDuplicate").exists())
				.andExpect(jsonPath(schemas + "ImportLinePreview.properties.suggestedCategoryId").exists())
				.andExpect(jsonPath(schemas + "ImportLinePreview.properties.problems").exists())
				// a confirmação: só os campos permitidos, nada de workspace, autoria, estado nem tipo
				.andExpect(jsonPath(schemas + "ImportConfirmRequest.properties.lines").exists())
				.andExpect(jsonPath(schemas + "ImportConfirmRequest.properties.workspaceId").doesNotExist())
				.andExpect(jsonPath(schemas + "ImportConfirmRequest.properties.accountId").doesNotExist())
				.andExpect(jsonPath(schemas + "ImportLineRequest.properties.key").exists())
				.andExpect(jsonPath(schemas + "ImportLineRequest.properties.occurredOn").exists())
				.andExpect(jsonPath(schemas + "ImportLineRequest.properties.description").exists())
				.andExpect(jsonPath(schemas + "ImportLineRequest.properties.amountCents").exists())
				.andExpect(jsonPath(schemas + "ImportLineRequest.properties.categoryId").exists())
				.andExpect(jsonPath(schemas + "ImportLineRequest.properties.createdBy").doesNotExist())
				.andExpect(jsonPath(schemas + "ImportLineRequest.properties.workspaceId").doesNotExist())
				.andExpect(jsonPath(schemas + "ImportLineRequest.properties.type").doesNotExist())
				.andExpect(jsonPath(schemas + "ImportLineRequest.properties.status").doesNotExist())
				.andExpect(jsonPath(schemas + "ImportConfirmResponse.properties.created").exists())
				.andExpect(jsonPath(schemas + "ImportConfirmResponse.properties.skipped").exists())
				// o lançamento diz se foi importado, e a chave de importação nunca sai
				.andExpect(jsonPath(schemas + "TransactionResponse.properties.imported").exists())
				.andExpect(jsonPath(schemas + "TransactionResponse.properties.importKey").doesNotExist())
				.andExpect(jsonPath(schemas + "TransactionResponse.properties.key").doesNotExist())
				// a listagem ganha o filtro "sem categoria"
				.andExpect(jsonPath("$.paths['/api/v1/workspaces/{wid}/transactions'].get.parameters[?(@.name=='uncategorized')]").exists());
	}

	@Test
	void membershipContractFileDeclaresTheSevenRoutes() throws IOException {
		Map<String, List<String>> operations = contractOperations(MEMBERSHIP_CONTRACT);

		assertThat(operations).containsOnlyKeys(
				"/api/v1/workspaces/{wid}/members",
				"/api/v1/workspaces/{wid}/members/{userId}",
				"/api/v1/workspaces/{wid}/invitations",
				"/api/v1/workspaces/{wid}/invitations/{id}",
				"/api/v1/invitations/accept");
		assertThat(operations.get("/api/v1/workspaces/{wid}/members")).containsExactly("get");
		assertThat(operations.get("/api/v1/workspaces/{wid}/members/{userId}")).containsExactlyInAnyOrder("put", "delete");
		assertThat(operations.get("/api/v1/workspaces/{wid}/invitations")).containsExactlyInAnyOrder("post", "get");
		assertThat(operations.get("/api/v1/workspaces/{wid}/invitations/{id}")).containsExactly("delete");
		assertThat(operations.get("/api/v1/invitations/accept")).containsExactly("post");
	}

	@Test
	void theMembershipRoutesAndFieldsAreInTheGeneratedDocsAndNothingElse() throws Exception {
		String members = "$.paths['/api/v1/workspaces/{wid}/members']";
		String member = "$.paths['/api/v1/workspaces/{wid}/members/{userId}']";
		String invitations = "$.paths['/api/v1/workspaces/{wid}/invitations']";
		String invitation = "$.paths['/api/v1/workspaces/{wid}/invitations/{id}']";
		String accept = "$.paths['/api/v1/invitations/accept']";
		String schemas = "$.components.schemas.";
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
				// o vínculo nasce só por convite aceito: não há POST em /members, e o convite não se edita nem se consulta por id
				.andExpect(jsonPath(members + ".get").exists())
				.andExpect(jsonPath(members + ".post").doesNotExist())
				.andExpect(jsonPath(member + ".put").exists())
				.andExpect(jsonPath(member + ".delete").exists())
				.andExpect(jsonPath(member + ".get").doesNotExist())
				.andExpect(jsonPath(member + ".patch").doesNotExist())
				.andExpect(jsonPath(invitations + ".post").exists())
				.andExpect(jsonPath(invitations + ".get").exists())
				.andExpect(jsonPath(invitations + ".put").doesNotExist())
				.andExpect(jsonPath(invitation + ".delete").exists())
				.andExpect(jsonPath(invitation + ".get").doesNotExist())
				.andExpect(jsonPath(invitation + ".put").doesNotExist())
				.andExpect(jsonPath(accept + ".post").exists())
				.andExpect(jsonPath(accept + ".get").doesNotExist())
				// o membro: o que se vê, nunca senha nem hash nem versão de sessão
				.andExpect(jsonPath(schemas + "MemberResponse.properties.userId").exists())
				.andExpect(jsonPath(schemas + "MemberResponse.properties.name").exists())
				.andExpect(jsonPath(schemas + "MemberResponse.properties.email").exists())
				.andExpect(jsonPath(schemas + "MemberResponse.properties.role").exists())
				.andExpect(jsonPath(schemas + "MemberResponse.properties.joinedAt").exists())
				.andExpect(jsonPath(schemas + "MemberResponse.properties.password").doesNotExist())
				.andExpect(jsonPath(schemas + "MemberResponse.properties.passwordHash").doesNotExist())
				.andExpect(jsonPath(schemas + "MemberResponse.properties.sessionVersion").doesNotExist())
				 // o código: só a resposta que cria o convite o tem; a lista de pendentes não
				.andExpect(jsonPath(schemas + "InvitationCreatedResponse.properties.code").exists())
				.andExpect(jsonPath(schemas + "InvitationResponse.properties.code").doesNotExist())
				.andExpect(jsonPath(schemas + "InvitationResponse.properties.codeHash").doesNotExist())
				.andExpect(jsonPath(schemas + "InvitationResponse.properties.email").exists())
				.andExpect(jsonPath(schemas + "InvitationResponse.properties.role").exists())
				.andExpect(jsonPath(schemas + "InvitationResponse.properties.expiresAt").exists())
				// os pedidos: só os campos permitidos
				.andExpect(jsonPath(schemas + "CreateInvitationRequest.properties.email").exists())
				.andExpect(jsonPath(schemas + "CreateInvitationRequest.properties.role").exists())
				.andExpect(jsonPath(schemas + "CreateInvitationRequest.properties.createdBy").doesNotExist())
				.andExpect(jsonPath(schemas + "CreateInvitationRequest.properties.workspaceId").doesNotExist())
				.andExpect(jsonPath(schemas + "CreateInvitationRequest.properties.code").doesNotExist())
				.andExpect(jsonPath(schemas + "ChangeRoleRequest.properties.role").exists())
				.andExpect(jsonPath(schemas + "AcceptInvitationRequest.properties.code").exists())
				.andExpect(jsonPath(schemas + "AcceptInvitationResponse.properties.workspaceId").exists())
				.andExpect(jsonPath(schemas + "AcceptInvitationResponse.properties.name").exists())
				.andExpect(jsonPath(schemas + "AcceptInvitationResponse.properties.role").exists());
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
				DASHBOARD_CONTRACT, BUDGETS_CONTRACT, MONTHLY_BUDGETS_CONTRACT, INVOICES_CONTRACT, PENDING_CONTRACT,
				INSTALLMENTS_CONTRACT, RECURRENCES_CONTRACT, MEMBERSHIP_CONTRACT, IMPORT_CONTRACT)) {
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
