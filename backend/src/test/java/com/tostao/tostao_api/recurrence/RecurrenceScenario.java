package com.tostao.tostao_api.recurrence;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * Cenário da 011. Contas {@code Corrente} (saldo inicial 500000), {@code Poupança} (0) e o cartão {@code Visa}
 * (limite 500000, fecha dia 20 e vence dia 28); categorias {@code Aluguel} e {@code Mercado} (despesa) e
 * {@code Salário} (receita). Não há lançamento nem recorrência: o que cada teste precisa ele cria, pela API ou pelo
 * banco.
 */
public record RecurrenceScenario(Fixture owner, UUID checking, UUID savings, UUID card, UUID rent, UUID groceries,
		UUID salary) {

	public static RecurrenceScenario create(WorkspaceFixtures fixtures) {
		Fixture fx = fixtures.workspaceWithOwner();
		UUID w = fx.workspaceId();
		UUID checking = fixtures.insertAccount(w, "Corrente", "CHECKING", 500_000, false);
		UUID savings = fixtures.insertAccount(w, "Poupança", "SAVINGS", 0, false);
		UUID card = fixtures.insertCard(w, "Visa", 0, 500_000L, 20, 28, false);
		UUID rent = fixtures.insertCategory(w, null, "Aluguel", "EXPENSE", false);
		UUID groceries = fixtures.insertCategory(w, null, "Mercado", "EXPENSE", false);
		UUID salary = fixtures.insertCategory(w, null, "Salário", "INCOME", false);
		return new RecurrenceScenario(fx, checking, savings, card, rent, groceries, salary);
	}

	/** Hoje, no fuso do sistema: o mesmo que o serviço usa para validar o início e gerar as ocorrências. */
	public static LocalDate today() {
		return LocalDate.now(ZoneId.of("America/Sao_Paulo"));
	}

	public static String recurrencesUrl(UUID workspaceId) {
		return "/api/v1/workspaces/" + workspaceId + "/recurrences";
	}

	/** {@code POST /recurrences} com o corpo dado. */
	public static ResultActions createRecurrence(MockMvc mvc, Fixture who, String json) throws Exception {
		return mvc.perform(post(recurrencesUrl(who.workspaceId())).cookie(session(who.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	public static ResultActions listRecurrences(MockMvc mvc, Fixture who) throws Exception {
		return mvc.perform(get(recurrencesUrl(who.workspaceId())).cookie(session(who.token())));
	}

	/** {@code GET /recurrences/{id}}; o id é texto para os testes de identificador malformado. */
	public static ResultActions getRecurrence(MockMvc mvc, Fixture who, Object id) throws Exception {
		return mvc.perform(get(recurrencesUrl(who.workspaceId()) + "/" + id).cookie(session(who.token())));
	}

	/** {@code POST /recurrences/{id}/end}; o id é texto para os testes de identificador malformado. */
	public static ResultActions endRecurrence(MockMvc mvc, Fixture who, Object id) throws Exception {
		return mvc.perform(post(recurrencesUrl(who.workspaceId()) + "/" + id + "/end").cookie(session(who.token())));
	}

	/** Uma recorrência válida (aluguel mensal de 150000 a partir de hoje) como mapa de campo para literal JSON. */
	public static Map<String, String> valid(RecurrenceScenario s) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("accountId", "\"" + s.checking() + "\"");
		fields.put("categoryId", "\"" + s.rent() + "\"");
		fields.put("type", "\"EXPENSE\"");
		fields.put("amountCents", "150000");
		fields.put("description", "\"Aluguel\"");
		fields.put("frequency", "\"MONTHLY\"");
		fields.put("startOn", "\"" + today() + "\"");
		return fields;
	}

	/** O corpo com os campos trocados; um valor {@code null} tira o campo do corpo. */
	public static String with(Map<String, String> base, String... overrides) {
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

	/** Impressão digital de todas as colunas de {@code recurrences} do workspace. */
	public static String recurrencesFingerprint(OwnerJdbc owner, UUID workspaceId) {
		return owner.client().sql("select coalesce(md5(string_agg(x::text, ',' order by x.id)), 'vazio') "
				+ "from recurrences x where x.workspace_id = :w").param("w", workspaceId).query(String.class).single();
	}

}
