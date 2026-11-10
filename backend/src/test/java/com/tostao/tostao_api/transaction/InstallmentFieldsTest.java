package com.tostao.tostao_api.transaction;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static com.tostao.tostao_api.transaction.PendingScenario.body;
import static com.tostao.tostao_api.transaction.PendingScenario.create;
import static com.tostao.tostao_api.transaction.PendingScenario.edit;
import static com.tostao.tostao_api.transaction.PendingScenario.list;
import static com.tostao.tostao_api.transaction.PendingScenario.transactionsUrl;
import static com.tostao.tostao_api.transaction.PendingScenario.transferBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
 * Todo lançamento devolve o grupo, o número e o total de parcelas: nulos nos que não são parcela, preenchidos nas
 * que são. Os campos entram na resposta sem mudar nenhum dos que já existiam (FR-011, FR-020).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class InstallmentFieldsTest {

	@Autowired
	MockMvc mvc;
	@Autowired
	OwnerJdbc owner;
	@Autowired
	SessionTokenService tokens;

	private WorkspaceFixtures fixtures() {
		return new WorkspaceFixtures(owner, tokens);
	}

	private static Map<String, Object> json(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$");
	}

	private static List<Map<String, Object>> items(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.items");
	}

	private ResultActions getById(InstallmentScenario s, UUID id) throws Exception {
		return mvc.perform(get(transactionsUrl(s.owner().workspaceId()) + "/" + id).cookie(session(s.owner().token())));
	}

	/** Os três campos existem na resposta (não foram omitidos) e valem o esperado; nulo quando não é parcela. */
	private static void assertFields(Map<String, Object> tx, UUID group, Integer number, Integer count) {
		assertThat(tx).containsKeys("installmentGroupId", "installmentNumber", "installmentCount");
		assertThat(tx.get("installmentGroupId")).isEqualTo(group == null ? null : group.toString());
		assertThat(tx.get("installmentNumber")).isEqualTo(number);
		assertThat(tx.get("installmentCount")).isEqualTo(count);
	}

	@Test
	void ordinaryTransactionsCarryTheThreeFieldsAsNull() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());

		Map<String, Object> expense = json(create(mvc, s.owner(),
				body("EXPENSE", s.checking(), 1_000, "2026-11-10", "Mercado", s.food(), null)).andExpect(status().isCreated()));
		Map<String, Object> income = json(create(mvc, s.owner(),
				body("INCOME", s.checking(), 2_000, "2026-11-11", "Salário", s.salary(), null)).andExpect(status().isCreated()));
		Map<String, Object> transfer = json(create(mvc, s.owner(),
				transferBody(s.checking(), s.savings(), 3_000, "2026-11-12", "Reserva", null)).andExpect(status().isCreated()));

		for (Map<String, Object> created : List.of(expense, income, transfer)) {
			assertFields(created, null, null, null);
			UUID id = UUID.fromString((String) created.get("id"));
			assertFields(json(getById(s, id).andExpect(status().isOk())), null, null, null);
		}
		List<Map<String, Object>> listed = items(list(mvc, s.owner(), "size=100").andExpect(status().isOk()));
		assertThat(listed).hasSize(4); // despesa, receita e as duas pontas da transferência
		listed.forEach(tx -> assertFields(tx, null, null, null));
	}

	@Test
	void anInstallmentCarriesItsGroupNumberAndCountEverywhere() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID group = UUID.randomUUID();
		UUID first = fixtures().insertInstallment(w, s.checking(), s.owner().userId(), s.gadgets(), group, 1, 3,
				100_000, LocalDate.of(2026, 11, 10), "Notebook (1/3)", "CLEARED", false);
		UUID second = fixtures().insertInstallment(w, s.checking(), s.owner().userId(), s.gadgets(), group, 2, 3,
				100_000, LocalDate.of(2026, 12, 10), "Notebook (2/3)", "PENDING", false);
		fixtures().insertLedgerTransaction(w, s.checking(), s.owner().userId(), s.food(), "EXPENSE", 500,
				LocalDate.of(2026, 11, 1), "Comum", false);

		assertFields(json(getById(s, first).andExpect(status().isOk())), group, 1, 3);
		assertFields(json(getById(s, second).andExpect(status().isOk())), group, 2, 3);

		List<Map<String, Object>> listed = items(list(mvc, s.owner(), "size=100").andExpect(status().isOk()));
		assertThat(listed).hasSize(3);
		for (Map<String, Object> tx : listed) {
			if (tx.get("id").equals(first.toString())) {
				assertFields(tx, group, 1, 3);
			}
			else if (tx.get("id").equals(second.toString())) {
				assertFields(tx, group, 2, 3);
			}
			else {
				assertFields(tx, null, null, null);
			}
		}
	}

	@Test
	void editingOrClearingAnInstallmentKeepsItsGroupNumberAndCount() throws Exception {
		InstallmentScenario s = InstallmentScenario.create(fixtures());
		UUID w = s.owner().workspaceId();
		UUID group = UUID.randomUUID();
		UUID id = fixtures().insertInstallment(w, s.checking(), s.owner().userId(), s.gadgets(), group, 2, 4, 25_000,
				LocalDate.of(2026, 12, 10), "Monitor (2/4)", "PENDING", false);

		// a edição não leva o tipo (nunca muda) nem as colunas de parcela
		String edited = "{\"accountId\":\"%s\",\"amountCents\":26000,\"occurredOn\":\"2026-12-15\",\"description\":\"Monitor (2/4) revisado\",\"categoryId\":\"%s\"}"
				.formatted(s.checking(), s.gadgets());
		assertFields(json(edit(mvc, s.owner(), id, edited).andExpect(status().isOk())), group, 2, 4);
		assertFields(json(PendingScenario.clear(mvc, s.owner(), id).andExpect(status().isOk())), group, 2, 4);
		assertFields(json(getById(s, id).andExpect(status().isOk())), group, 2, 4);
	}

}
