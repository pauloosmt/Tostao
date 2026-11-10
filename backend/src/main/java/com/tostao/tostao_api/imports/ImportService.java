package com.tostao.tostao_api.imports;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.imports.dto.ImportLinePreview;
import com.tostao.tostao_api.imports.dto.ImportPreview;
import com.tostao.tostao_api.transaction.TransactionType;
import com.tostao.tostao_api.transaction.UsableRefs;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/**
 * Importação de extratos (spec 013): a prévia lê o arquivo e compara com a conta sem gravar nada. Duas camadas de
 * isolamento: {@link WorkspaceScoped} (participação e papel) e o RLS cuidam do workspace, e toda consulta daqui também
 * filtra explicitamente por {@code workspace_id}. O arquivo é só dado: nada do conteúdo vai para log nem para mensagem.
 */
@Service
public class ImportService {

	private final JdbcClient jdbc;
	private final UsableRefs refs;

	public ImportService(JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.refs = new UsableRefs(jdbc);
	}

	/**
	 * Prévia do extrato: confere a conta (existe no workspace e está ativa), lê o arquivo e marca cada linha como
	 * existente, possível duplicata ou com sugestão de categoria. Não grava nada.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public ImportPreview preview(UUID workspaceId, UUID accountId, byte[] file) {
		refs.requireUsableAccount(workspaceId, accountId);
		StatementParser.Parsed parsed = StatementParser.parse(file);

		List<StatementLine> valid = parsed.lines().stream().filter(StatementLine::valid).toList();
		Set<String> existingKeys = existingKeys(workspaceId, accountId, valid);
		Set<String> movements = movementsWithoutKey(workspaceId, accountId, valid);
		Map<String, UUID> suggestions = suggestions(workspaceId, valid);

		List<ImportLinePreview> lines = new ArrayList<>(parsed.lines().size());
		int existing = 0;
		int invalid = 0;
		for (StatementLine line : parsed.lines()) {
			if (!line.valid()) {
				invalid++;
				lines.add(new ImportLinePreview(line.line(), line.occurredOn(), line.description(), line.amountCents(),
						typeOf(line.amountCents()), null, false, false, null, line.problems()));
				continue;
			}
			boolean exists = existingKeys.contains(line.key());
			if (exists) {
				existing++;
			}
			TransactionType type = typeOf(line.amountCents());
			lines.add(new ImportLinePreview(line.line(), line.occurredOn(), line.description(), line.amountCents(), type,
					line.key(), exists, movements.contains(movement(line.occurredOn(), line.amountCents())),
					suggestions.get(suggestionKey(line.description(), type)), line.problems()));
		}
		int total = lines.size();
		return new ImportPreview(parsed.format().name(), total, total - existing - invalid, existing, invalid, lines);
	}

	/** As chaves destas linhas que já existem na conta, inclusive em lançamentos excluídos. */
	private Set<String> existingKeys(UUID workspaceId, UUID accountId, List<StatementLine> valid) {
		if (valid.isEmpty()) {
			return Set.of();
		}
		return jdbc.sql("""
				select import_key from transactions
				where workspace_id = :workspaceId and account_id = :accountId and import_key in (:keys)
				""")
				.param("workspaceId", workspaceId)
				.param("accountId", accountId)
				.param("keys", valid.stream().map(StatementLine::key).toList())
				.query(String.class)
				.set();
	}

	/**
	 * Data e valor dos lançamentos <b>sem chave</b> da conta, não excluídos, no intervalo do arquivo: se uma linha do
	 * extrato casa com um deles, é uma possível duplicata (a pessoa pode ter digitado a compra à mão).
	 */
	private Set<String> movementsWithoutKey(UUID workspaceId, UUID accountId, List<StatementLine> valid) {
		if (valid.isEmpty()) {
			return Set.of();
		}
		LocalDate min = valid.stream().map(StatementLine::occurredOn).min(LocalDate::compareTo).orElseThrow();
		LocalDate max = valid.stream().map(StatementLine::occurredOn).max(LocalDate::compareTo).orElseThrow();
		Set<String> movements = new HashSet<>();
		jdbc.sql("""
				select occurred_on, amount_cents from transactions
				where workspace_id = :workspaceId and account_id = :accountId and deleted_at is null
				  and import_key is null and occurred_on between :min and :max
				""")
				.param("workspaceId", workspaceId)
				.param("accountId", accountId)
				.param("min", min)
				.param("max", max)
				.query(rs -> {
					movements.add(movement(rs.getObject("occurred_on", LocalDate.class), rs.getLong("amount_cents")));
				});
		return movements;
	}

	/**
	 * Para cada descrição do arquivo (sem diferença de maiúsculas, acentos e espaços) e tipo, a categoria do lançamento
	 * mais recente do workspace, não excluído e com categoria ativa. Desempate pela data e, depois, pela criação.
	 */
	private Map<String, UUID> suggestions(UUID workspaceId, List<StatementLine> valid) {
		if (valid.isEmpty()) {
			return Map.of();
		}
		Set<String> wanted = new HashSet<>();
		for (StatementLine line : valid) {
			wanted.add(suggestionKey(line.description(), typeOf(line.amountCents())));
		}
		Map<String, UUID> found = new HashMap<>();
		jdbc.sql("""
				select t.description, t.type, t.category_id
				from transactions t
				join categories c on c.id = t.category_id and c.workspace_id = t.workspace_id
				where t.workspace_id = :workspaceId and t.deleted_at is null and not c.archived
				  and t.type in ('INCOME', 'EXPENSE')
				order by t.occurred_on desc, t.created_at desc
				""")
				.param("workspaceId", workspaceId)
				.query(rs -> {
					String key = suggestionKey(rs.getString("description"), TransactionType.valueOf(rs.getString("type")));
					if (wanted.contains(key)) {
						found.putIfAbsent(key, rs.getObject("category_id", UUID.class));
					}
				});
		return found;
	}

	private static String suggestionKey(String description, TransactionType type) {
		return type + "|" + ImportKeys.normalize(description);
	}

	private static String movement(LocalDate date, long cents) {
		return date + "|" + cents;
	}

	private static TransactionType typeOf(Long cents) {
		if (cents == null) {
			return null;
		}
		return cents > 0 ? TransactionType.INCOME : TransactionType.EXPENSE;
	}

}
