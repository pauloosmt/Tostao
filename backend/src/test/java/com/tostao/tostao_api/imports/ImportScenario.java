package com.tostao.tostao_api.imports;

import static com.tostao.tostao_api.support.AuthSupport.session;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.tostao.tostao_api.TestcontainersConfiguration.OwnerJdbc;
import com.tostao.tostao_api.support.WorkspaceFixtures;
import com.tostao.tostao_api.support.WorkspaceFixtures.Fixture;

/**
 * Cenário da 013: um workspace com o dono, um editor e um leitor, duas contas e três categorias, mais uma pessoa de fora
 * com o próprio workspace. Helpers das duas rotas e de semeadura de lançamentos pela conexão do dono.
 */
public record ImportScenario(Fixture owner, Fixture editor, Fixture viewer, Fixture outsider, UUID checking, UUID savings,
		UUID food, UUID transport, UUID salary) {

	public static ImportScenario create(WorkspaceFixtures fixtures) {
		Fixture owner = fixtures.workspaceWithOwner();
		UUID w = owner.workspaceId();
		Fixture editor = fixtures.addMember(w, "EDITOR");
		Fixture viewer = fixtures.addMember(w, "VIEWER");
		return new ImportScenario(owner, editor, viewer, fixtures.workspaceWithOwner(),
				fixtures.insertAccount(w, "Corrente", "CHECKING", 100_000, false),
				fixtures.insertAccount(w, "Poupança", "SAVINGS", 0, false),
				fixtures.insertCategory(w, null, "Alimentação", "EXPENSE", false),
				fixtures.insertCategory(w, null, "Transporte", "EXPENSE", false),
				fixtures.insertCategory(w, null, "Salário", "INCOME", false));
	}

	public UUID workspace() {
		return owner.workspaceId();
	}

	public static String previewUrl(UUID workspaceId, Object accountId) {
		return "/api/v1/workspaces/" + workspaceId + "/accounts/" + accountId + "/import/preview";
	}

	public static String confirmUrl(UUID workspaceId, Object accountId) {
		return "/api/v1/workspaces/" + workspaceId + "/accounts/" + accountId + "/import";
	}

	/** Envia os bytes na parte {@code file} da prévia. */
	public static ResultActions preview(MockMvc mvc, Fixture who, UUID workspaceId, Object accountId, byte[] bytes) throws Exception {
		return mvc.perform(multipart(previewUrl(workspaceId, accountId))
				.file(new MockMultipartFile("file", "extrato", "application/octet-stream", bytes)).cookie(session(who.token())));
	}

	/** Envia um dos arquivos de {@code src/test/resources/statements}. */
	public static ResultActions preview(MockMvc mvc, Fixture who, UUID workspaceId, Object accountId, String statement) throws Exception {
		return preview(mvc, who, workspaceId, accountId, Statements.bytes(statement));
	}

	/** {@code POST /import} com o corpo dado. */
	public static ResultActions confirm(MockMvc mvc, Fixture who, UUID workspaceId, Object accountId, String json) throws Exception {
		return mvc.perform(post(confirmUrl(workspaceId, accountId)).cookie(session(who.token()))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** Uma linha do corpo da confirmação. {@code categoryId} nulo omite o campo. */
	public static String line(String key, String date, String description, long cents, UUID categoryId) {
		return "{\"key\":\"%s\",\"occurredOn\":\"%s\",\"description\":\"%s\",\"amountCents\":%d%s}".formatted(key, date,
				description, cents, categoryId == null ? "" : ",\"categoryId\":\"" + categoryId + "\"");
	}

	public static String body(String... lines) {
		return "{\"lines\":[" + String.join(",", lines) + "]}";
	}

	/** Semeia um lançamento pela conexão do dono: despesa se o valor é negativo, receita se positivo. */
	public static UUID seed(OwnerJdbc owner, UUID workspaceId, UUID accountId, UUID userId, UUID categoryId, long cents,
			LocalDate date, String description, String importKey, boolean deleted) {
		return owner.client().sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on, description,
				                          created_by, import_key, deleted_at)
				values (:w, :a, :c, :t, :m, :d, :s, :u, :k, case when :x then now() else null end)
				returning id""")
				.param("w", workspaceId).param("a", accountId).param("c", categoryId).param("t", cents > 0 ? "INCOME" : "EXPENSE")
				.param("m", cents).param("d", date).param("s", description).param("u", userId).param("k", importKey)
				.param("x", deleted).query(UUID.class).single();
	}

	/** Impressão digital de todas as linhas de {@code transactions} do workspace. */
	public static String fingerprint(OwnerJdbc owner, UUID workspaceId) {
		return owner.client().sql("select coalesce(md5(string_agg(x::text, ',' order by x.id)), 'vazio') "
				+ "from transactions x where x.workspace_id = :w").param("w", workspaceId).query(String.class).single();
	}

	public static long count(OwnerJdbc owner, UUID accountId) {
		return owner.client().sql("select count(*) from transactions where account_id = :a").param("a", accountId)
				.query(Long.class).single();
	}

}
