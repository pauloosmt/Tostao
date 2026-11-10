package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchase;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchaseBody;
import static com.tostao.tostao_api.transaction.InstallmentScenario.purchasesUrl;
import static com.tostao.tostao_api.transaction.PendingScenario.clear;
import static com.tostao.tostao_api.transaction.PendingScenario.list;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsFingerprint;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsUrl;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.tostao.tostao_api.TestcontainersConfiguration;
import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.auth.SessionTokenService;
import com.tostao.tostao_api.support.WorkspaceFixtures;

/** História 1: registrar uma compra parcelada de uma vez, todas as parcelas ou nenhuma. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InstallmentPurchaseTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	/** Um registro válido (R$ 3.000,00 em 3, a partir de 2026-11-10) como mapa de campo para literal JSON. */
	private static Map<String, String> valid(InstallmentScenario s) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("accountId", "\"" + s.checking() + "\"");
		fields.put("categoryId", "\"" + s.gadgets() + "\"");
		fields.put("totalCents", "300000");
		fields.put("installmentCount", "3");
		fields.put("firstOccurredOn", "\"2026-11-10\"");
		fields.put("description", "\"Notebook\"");
		return fields;
	}

	/** O corpo com os campos trocados; um valor {@code null} tira o campo do corpo. */
	private static String with(Map<String, String> base, String... overrides) {
		Map<String, String> fields = new LinkedHashMap<>(base);
		for (int i = 0; i < overrides.length; i += 2) {
			if (overrides[i + 1] == null) {
				fields.remove(overrides[i]);
			}
			else {
				fields.put(overrides[i], overrides[i + 1]);
			}
		}
		StringBuilder json = new StringBuilder("{");
		fields.forEach((k, v) -> json.append(json.length() > 1 ? "," : "").append('"').append(k).append("\":").append(v));
		return json.append('}').toString();
	}

	private static List<String> errorFields(ResultActions result) throws Exception {
		return JsonPath.read(body(result), "$.errors[*].field");
	}

	// ---- registro ---------------------------------------------------------------------------------------------

	@Test
	void threeThousandInThreeCreatesThreeLinkedInstallmentsOneMonthApart() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		MvcResult created = purchase(mvc, s.owner(), purchaseBody(s.checking(), s.gadgets(), 300_000, 3, "2026-11-10",
				"Notebook", null)).andExpect(status().isCreated())
				.andExpect(jsonPath("$.description").value("Notebook"))
				.andExpect(jsonPath("$.installmentCount").value(3))
				.andExpect(jsonPath("$.accountId").value(s.checking().toString()))
				.andExpect(jsonPath("$.categoryId").value(s.gadgets().toString()))
				.andExpect(jsonPath("$.totalCents").value(300_000))
				.andExpect(jsonPath("$.clearedCents").value(100_000))
				.andExpect(jsonPath("$.pendingCents").value(200_000)).andReturn();
		String json = created.getResponse().getContentAsString();
		UUID group = UUID.fromString(JsonPath.read(json, "$.groupId"));

		assertThat(created.getResponse().getHeader("Location"))
				.isEqualTo(purchasesUrl(s.owner().workspaceId()) + "/" + group);
		assertThat(JsonPath.<List<Integer>>read(json, "$.installments[*].amountCents"))
				.containsExactly(-100_000, -100_000, -100_000);
		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].occurredOn"))
				.containsExactly("2026-11-10", "2026-12-10", "2027-01-10");
		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].description"))
				.containsExactly("Notebook (1/3)", "Notebook (2/3)", "Notebook (3/3)");
		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].status"))
				.containsExactly("CLEARED", "PENDING", "PENDING");
		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].installmentGroupId"))
				.containsOnly(group.toString());
		assertThat(JsonPath.<List<Integer>>read(json, "$.installments[*].installmentNumber")).containsExactly(1, 2, 3);
		assertThat(JsonPath.<List<Integer>>read(json, "$.installments[*].installmentCount")).containsOnly(3);
		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].type")).containsOnly("EXPENSE");
		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].categoryId")).containsOnly(s.gadgets().toString());
		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].accountId")).containsOnly(s.checking().toString());
	}

	@Test
	void theTotalIsSplitInExactCentsWithTheRemainderOnTheFirstInstallments() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		String json = body(purchase(mvc, s.owner(), purchaseBody(s.checking(), s.food(), 10_000, 3, "2026-11-12", "Café",
				null)).andExpect(status().isCreated()).andExpect(jsonPath("$.totalCents").value(10_000)));

		assertThat(JsonPath.<List<Integer>>read(json, "$.installments[*].amountCents"))
				.containsExactly(-3_334, -3_333, -3_333);
	}

	@Test
	void theEndOfMonthAdjustmentDoesNotAccumulate() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		String json = body(purchase(mvc, s.owner(), purchaseBody(s.checking(), s.food(), 4_000, 4, "2027-01-31",
				"Fim de mês", null)).andExpect(status().isCreated()));

		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].occurredOn"))
				.containsExactly("2027-01-31", "2027-02-28", "2027-03-31", "2027-04-30");
	}

	@Test
	void aLeapYearKeepsTheTwentyNinthOfFebruary() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		String json = body(purchase(mvc, s.owner(), purchaseBody(s.checking(), s.food(), 3_000, 3, "2028-01-31",
				"Bissexto", null)).andExpect(status().isCreated()));

		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].occurredOn"))
				.containsExactly("2028-01-31", "2028-02-29", "2028-03-31");
	}

	@Test
	void askingForAPendingFirstInstallmentMakesEveryInstallmentPending() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		String json = body(purchase(mvc, s.owner(), purchaseBody(s.checking(), s.gadgets(), 300_000, 3, "2026-11-10",
				"Notebook", "PENDING")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.clearedCents").value(0)).andExpect(jsonPath("$.pendingCents").value(300_000)));

		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].status")).containsOnly("PENDING");
		// e pedir CLEARED explicitamente é o mesmo que não pedir
		String explicit = body(purchase(mvc, s.owner(), purchaseBody(s.checking(), s.gadgets(), 300_000, 3, "2026-11-10",
				"Notebook", "CLEARED")).andExpect(status().isCreated()));
		assertThat(JsonPath.<List<String>>read(explicit, "$.installments[*].status"))
				.containsExactly("CLEARED", "PENDING", "PENDING");
	}

	@Test
	void theNotesAreCopiedToEveryInstallmentAndTheAuthorIsTheSessionUser() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		String json = body(purchase(mvc, s.owner(), with(valid(s), "notes", "\"Garantia de 12 meses\"")).andExpect(
				status().isCreated()));

		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].notes")).containsOnly("Garantia de 12 meses");
		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].createdBy"))
				.containsOnly(s.owner().userId().toString());
		// observação vazia vira nenhuma
		String blank = body(purchase(mvc, s.owner(), with(valid(s), "notes", "\"   \"")).andExpect(status().isCreated()));
		assertThat(JsonPath.<List<String>>read(blank, "$.installments[*].notes")).containsOnly((String) null);
	}

	@Test
	void twoAndFortyEightInstallmentsAreAccepted() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		String two = body(purchase(mvc, s.owner(), with(valid(s), "installmentCount", "2", "totalCents", "101"))
				.andExpect(status().isCreated()));
		assertThat(JsonPath.<List<Integer>>read(two, "$.installments[*].amountCents")).containsExactly(-51, -50);

		String max = body(purchase(mvc, s.owner(), with(valid(s), "installmentCount", "48", "totalCents", "1000000000000"))
				.andExpect(status().isCreated()));
		assertThat(JsonPath.<List<Integer>>read(max, "$.installments[*].installmentNumber")).hasSize(48);
		assertThat(JsonPath.<Number>read(max, "$.totalCents").longValue()).isEqualTo(1_000_000_000_000L);
	}

	@Test
	void aDescriptionOf190CharactersFitsInEveryInstallmentDescription() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		String long190 = "x".repeat(190);

		String json = body(purchase(mvc, s.owner(), with(valid(s), "description", "\"" + long190 + "\"",
				"installmentCount", "48", "totalCents", "48000")).andExpect(status().isCreated())
				.andExpect(jsonPath("$.description").value(long190)));

		assertThat(JsonPath.<List<String>>read(json, "$.installments[*].description").get(47))
				.isEqualTo(long190 + " (48/48)");
	}

	// ---- validação: nada é gravado ----------------------------------------------------------------------------

	private void refuse(InstallmentScenario s, int expectedStatus, String field, String json) throws Exception {
		String before = transactionsFingerprint(owner, s.owner().workspaceId());
		ResultActions result = purchase(mvc, s.owner(), json).andExpect(status().is(expectedStatus));
		if (field != null) {
			assertThat(errorFields(result)).as(json).contains(field);
		}
		assertThat(transactionsFingerprint(owner, s.owner().workspaceId())).as("nada gravado para " + json)
				.isEqualTo(before);
	}

	@Test
	void anInvalidInstallmentCountIsRefusedOnItsField() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		for (String count : new String[] { "1", "0", "-1", "49", "100", "3.5", "\"abc\"", "null", "true", null }) {
			refuse(s, 400, "installmentCount", with(valid(s), "installmentCount", count));
		}
	}

	@Test
	void anInvalidTotalIsRefusedOnItsField() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		for (String total : new String[] { "0", "-1", "10.5", "1000000000001", "null", "\"abc\"", null }) {
			refuse(s, 400, "totalCents", with(valid(s), "totalCents", total));
		}
		// menos de 1 centavo por parcela
		refuse(s, 400, "totalCents", with(valid(s), "totalCents", "2"));
		refuse(s, 400, "totalCents", with(valid(s), "totalCents", "47", "installmentCount", "48"));
	}

	@Test
	void aDateOutsideTheRangeIsRefusedOnTheFirstDateField() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		for (String date : new String[] { "\"1999-12-31\"", "\"2101-01-01\"", "\"texto\"", "\"2026-02-30\"", "null", null }) {
			refuse(s, 400, "firstOccurredOn", with(valid(s), "firstOccurredOn", date));
		}
		// a última parcela passaria de 2100-12-31
		refuse(s, 400, "firstOccurredOn", with(valid(s), "firstOccurredOn", "\"2099-12-31\"", "installmentCount", "24"));
		refuse(s, 400, "firstOccurredOn", with(valid(s), "firstOccurredOn", "\"2100-12-31\"", "installmentCount", "2"));
	}

	@Test
	void anInvalidDescriptionIsRefusedOnItsField() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		for (String description : new String[] { "\"\"", "\"   \"", "\"" + "x".repeat(191) + "\"", "null", null }) {
			refuse(s, 400, "description", with(valid(s), "description", description));
		}
		refuse(s, 400, "notes", with(valid(s), "notes", "\"" + "n".repeat(1001) + "\""));
	}

	@Test
	void anInvalidFirstInstallmentStatusIsRefusedOnItsField() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		for (String status : new String[] { "\"OUTRO\"", "\"\"", "\"cleared\"", "1" }) {
			refuse(s, 400, "firstInstallmentStatus", with(valid(s), "firstInstallmentStatus", status));
		}
	}

	@Test
	void missingAccountOrCategoryIsRefusedOnTheField() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		refuse(s, 400, "accountId", with(valid(s), "accountId", null));
		refuse(s, 400, "categoryId", with(valid(s), "categoryId", null));
		refuse(s, 400, "accountId", with(valid(s), "accountId", "\"nao-e-uuid\""));
	}

	@Test
	void fieldsOutsideTheAllowedListAreRefused() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		for (String extra : new String[] { "workspaceId", "createdBy", "installmentGroupId", "installmentNumber", "status",
				"id", "deletedAt", "type", "amountCents" }) {
			refuse(s, 400, null, with(valid(s), extra, "\"" + UUID.randomUUID() + "\""));
		}
	}

	@Test
	void anIncomeCategoryIsRefusedOnTheCategoryField() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		refuse(s, 400, "categoryId", with(valid(s), "categoryId", "\"" + s.salary() + "\""));
	}

	@Test
	void anArchivedAccountOrCategoryIsAConflict() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID archivedAccount = fixtures().insertAccount(w, "Antiga", "CHECKING", 0, true);
		UUID archivedCategory = fixtures().insertCategory(w, null, "Velha", "EXPENSE", true);

		refuse(s, 409, null, with(valid(s), "accountId", "\"" + archivedAccount + "\""));
		refuse(s, 409, null, with(valid(s), "categoryId", "\"" + archivedCategory + "\""));
	}

	@Test
	void anUnknownOrForeignAccountOrCategoryIsNotFound() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		InstallmentScenario other = InstallmentScenario.create(fixtures());

		refuse(s, 404, null, with(valid(s), "accountId", "\"" + UUID.randomUUID() + "\""));
		refuse(s, 404, null, with(valid(s), "categoryId", "\"" + UUID.randomUUID() + "\""));
		refuse(s, 404, null, with(valid(s), "accountId", "\"" + other.checking() + "\""));
		refuse(s, 404, null, with(valid(s), "categoryId", "\"" + other.gadgets() + "\""));
	}

	@Test
	void aMalformedBodyIsRefusedAndNothingIsWritten() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		refuse(s, 400, null, "{");
		refuse(s, 400, null, "[]");
		refuse(s, 400, null, "");
	}

	@Test
	void theResponseIsNeverCached() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		purchase(mvc, s.owner(), with(valid(s))).andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
	}

	// ---- cada parcela é um lançamento comum -------------------------------------------------------------------

	@Test
	void everyInstallmentIsAnOrdinaryTransaction() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		String json = body(purchase(mvc, s.owner(), with(valid(s))).andExpect(status().isCreated()));
		List<String> ids = JsonPath.read(json, "$.installments[*].id");
		UUID group = UUID.fromString(JsonPath.read(json, "$.groupId"));

		// aparece na listagem, com o grupo, o número e o total
		String listed = body(list(mvc, s.owner(), "size=100").andExpect(status().isOk()));
		assertThat(JsonPath.<List<String>>read(listed, "$.items[*].id")).containsExactlyInAnyOrderElementsOf(ids);

		// efetiva a segunda
		clear(mvc, s.owner(), UUID.fromString(ids.get(1))).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CLEARED"));

		// edita a terceira: valor, data e descrição mudam; grupo, número e total, não
		String edited = "{\"accountId\":\"%s\",\"amountCents\":120000,\"occurredOn\":\"2027-01-15\",\"description\":\"Notebook (3/3) ajustado\",\"categoryId\":\"%s\"}"
				.formatted(s.checking(), s.gadgets());
		mvc.perform(put(transactionsUrl(s.owner().workspaceId()) + "/" + ids.get(2)).cookie(session(s.owner().token()))
				.contentType(MediaType.APPLICATION_JSON).content(edited)).andExpect(status().isOk())
				.andExpect(jsonPath("$.amountCents").value(-120_000))
				.andExpect(jsonPath("$.installmentGroupId").value(group.toString()))
				.andExpect(jsonPath("$.installmentNumber").value(3))
				.andExpect(jsonPath("$.installmentCount").value(3));

		// exclui a primeira
		mvc.perform(delete(transactionsUrl(s.owner().workspaceId()) + "/" + ids.get(0)).cookie(session(s.owner().token())))
				.andExpect(status().isNoContent());
	}

	@Test
	void theGroupNumberAndCountCannotBeChangedByAnEdit() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		String json = body(purchase(mvc, s.owner(), with(valid(s))).andExpect(status().isCreated()));
		String second = JsonPath.<List<String>>read(json, "$.installments[*].id").get(1);
		String base = "{\"accountId\":\"%s\",\"amountCents\":100000,\"occurredOn\":\"2026-12-10\",\"description\":\"Notebook (2/3)\",\"categoryId\":\"%s\""
				.formatted(s.checking(), s.gadgets());

		for (String extra : new String[] { "\"installmentGroupId\":\"" + UUID.randomUUID() + "\"",
				"\"installmentNumber\":1", "\"installmentCount\":5" }) {
			String before = transactionsFingerprint(owner, s.owner().workspaceId());
			mvc.perform(put(transactionsUrl(s.owner().workspaceId()) + "/" + second).cookie(session(s.owner().token()))
					.contentType(MediaType.APPLICATION_JSON).content(base + "," + extra + "}"))
					.andExpect(status().isBadRequest());
			assertThat(transactionsFingerprint(owner, s.owner().workspaceId())).as(extra).isEqualTo(before);
		}
	}

}
