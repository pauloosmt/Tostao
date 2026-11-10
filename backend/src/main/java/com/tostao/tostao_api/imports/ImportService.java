package com.tostao.tostao_api.imports;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

import com.tostao.tostao_api.account.MoneyLimits;
import com.tostao.tostao_api.category.CategoryKind;
import com.tostao.tostao_api.common.error.CategoryNotFoundException;
import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.common.error.TransactionConflictException;
import com.tostao.tostao_api.imports.dto.ImportConfirmRequest;
import com.tostao.tostao_api.imports.dto.ImportConfirmResponse;
import com.tostao.tostao_api.imports.dto.ImportLinePreview;
import com.tostao.tostao_api.imports.dto.ImportLineRequest;
import com.tostao.tostao_api.imports.dto.ImportPreview;
import com.tostao.tostao_api.transaction.TransactionService;
import com.tostao.tostao_api.transaction.TransactionType;
import com.tostao.tostao_api.transaction.UsableRefs;
import com.tostao.tostao_api.transaction.dto.TransactionResponse;
import com.tostao.tostao_api.workspace.CurrentUser;
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
		// uma linha por descrição e tipo (a mais recente): milhares de lançamentos viram poucas linhas
		// e cada texto distinto é normalizado uma vez só
		record Candidate(UUID categoryId, LocalDate occurredOn, Instant createdAt) {
		}
		Map<String, Candidate> best = new HashMap<>();
		jdbc.sql("""
				select distinct on (t.description, t.type)
				       t.description, t.type, t.category_id, t.occurred_on, t.created_at
				from transactions t
				join categories c on c.id = t.category_id and c.workspace_id = t.workspace_id
				where t.workspace_id = :workspaceId and t.deleted_at is null and not c.archived
				  and t.type in ('INCOME', 'EXPENSE')
				order by t.description, t.type, t.occurred_on desc, t.created_at desc
				""")
				.param("workspaceId", workspaceId)
				.query(rs -> {
					String key = TransactionType.valueOf(rs.getString("type")) + "|" + ImportKeys.normalize(rs.getString("description"));
					if (!wanted.contains(key)) {
						return;
					}
					Candidate candidate = new Candidate(rs.getObject("category_id", UUID.class), rs.getObject("occurred_on", LocalDate.class),
							rs.getObject("created_at", OffsetDateTime.class).toInstant());
					best.merge(key, candidate, (a, b) -> {
						int byDate = b.occurredOn().compareTo(a.occurredOn());
						return byDate > 0 || (byDate == 0 && b.createdAt().isAfter(a.createdAt())) ? b : a;
					});
				});
		Map<String, UUID> found = new HashMap<>();
		best.forEach((key, candidate) -> found.put(key, candidate.categoryId()));
		return found;
	}

	// ---- confirmação -------------------------------------------------------------------------------------------

	private static final int INSERT_BLOCK = 100;

	/** O que a confirmação precisa saber de uma categoria. */
	private record CategoryInfo(CategoryKind kind, boolean archived) {
	}

	/**
	 * Confirma a importação: cada linha vira um lançamento <b>efetivado</b> da conta, receita se o valor é positivo e
	 * despesa se é negativo, com a pessoa logada como autora. Tudo é conferido antes de gravar (conta, linhas, categorias);
	 * uma linha inválida recusa a confirmação inteira. Linhas cuja chave já existe na conta, inclusive em lançamento
	 * excluído, são ignoradas. A trava por conta serializa confirmações simultâneas e o índice único é a garantia final de
	 * não duplicar. Nenhuma exceção é capturada dentro da transação.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public ImportConfirmResponse confirm(UUID workspaceId, UUID accountId, ImportConfirmRequest request) {
		refs.requireUsableAccount(workspaceId, accountId);
		List<ImportLineRequest> lines = request.lines();
		Map<UUID, CategoryInfo> categories = lockCategories(workspaceId, lines);
		requireValidLines(lines, categories);

		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 1))").param("key", accountId.toString())
				.query((rs, i) -> 1).single();

		UUID userId = CurrentUser.id();
		List<UUID> createdIds = new ArrayList<>();
		for (int from = 0; from < lines.size(); from += INSERT_BLOCK) {
			createdIds.addAll(insertBlock(workspaceId, accountId, userId, lines.subList(from, Math.min(from + INSERT_BLOCK, lines.size()))));
		}
		return new ImportConfirmResponse(load(workspaceId, createdIds), lines.size() - createdIds.size());
	}

	/** As categorias usadas pelas linhas, travadas em modo compartilhado (arquivar espera esta confirmação). */
	private Map<UUID, CategoryInfo> lockCategories(UUID workspaceId, List<ImportLineRequest> lines) {
		List<UUID> ids = lines.stream().map(ImportLineRequest::categoryId).filter(java.util.Objects::nonNull).distinct().toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<UUID, CategoryInfo> found = new HashMap<>();
		jdbc.sql("""
				select id, kind, archived from categories
				where workspace_id = :workspaceId and id in (:ids)
				order by id
				for share
				""")
				.param("workspaceId", workspaceId)
				.param("ids", ids)
				.query(rs -> {
					found.put(rs.getObject("id", UUID.class),
							new CategoryInfo(CategoryKind.valueOf(rs.getString("kind")), rs.getBoolean("archived")));
				});
		return found;
	}

	/** Confere cada linha, na ordem, e aponta a primeira inválida como {@code lines[N].campo}. */
	private static void requireValidLines(List<ImportLineRequest> lines, Map<UUID, CategoryInfo> categories) {
		Set<String> keys = new HashSet<>();
		for (int i = 0; i < lines.size(); i++) {
			ImportLineRequest line = lines.get(i);
			String at = "lines[" + i + "].";
			if (line.key().indexOf('\u0000') >= 0) {
				throw new FieldValidationException(at + "key", "contém caractere inválido");
			}
			if (line.description().indexOf('\u0000') >= 0) {
				throw new FieldValidationException(at + "description", "contém caractere inválido");
			}
			if (line.occurredOn().isBefore(StatementParser.MIN_DATE) || line.occurredOn().isAfter(StatementParser.MAX_DATE)) {
				throw new FieldValidationException(at + "occurredOn", "deve estar entre 2000-01-01 e 2100-12-31");
			}
			long cents = line.amountCents();
			if (cents == 0 || Math.abs(cents) > MoneyLimits.MAX_CENTS) {
				throw new FieldValidationException(at + "amountCents",
						"deve ser diferente de zero e de até 1.000.000.000.000 centavos em módulo");
			}
			if (!keys.add(line.key())) {
				throw new FieldValidationException(at + "key", "chave repetida na confirmação");
			}
			if (line.categoryId() != null) {
				CategoryInfo category = categories.get(line.categoryId());
				if (category == null) {
					throw new CategoryNotFoundException();
				}
				CategoryKind wanted = cents > 0 ? CategoryKind.INCOME : CategoryKind.EXPENSE;
				if (category.kind() != wanted) {
					throw new FieldValidationException(at + "categoryId",
							wanted == CategoryKind.INCOME ? "deve ser uma categoria de receita" : "deve ser uma categoria de despesa");
				}
				if (category.archived()) {
					throw new TransactionConflictException("A categoria está arquivada");
				}
			}
		}
	}

	/** Insere um bloco de linhas e devolve os ids das que entraram (as de chave já existente não voltam). */
	private List<UUID> insertBlock(UUID workspaceId, UUID accountId, UUID userId, List<ImportLineRequest> block) {
		StringBuilder sql = new StringBuilder("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, status, created_by, import_key)
				values\s""");
		for (int i = 0; i < block.size(); i++) {
			sql.append(i == 0 ? "" : ", ").append("(:workspaceId, :accountId, cast(:c%d as uuid), :t%d, :m%d, :d%d, :s%d, 'CLEARED', :userId, :k%d)"
					.formatted(i, i, i, i, i, i));
		}
		sql.append("\non conflict (account_id, import_key) where import_key is not null do nothing\nreturning id");

		JdbcClient.StatementSpec statement = jdbc.sql(sql.toString())
				.param("workspaceId", workspaceId)
				.param("accountId", accountId)
				.param("userId", userId);
		for (int i = 0; i < block.size(); i++) {
			ImportLineRequest line = block.get(i);
			statement = statement
					.param("c" + i, line.categoryId())
					.param("t" + i, (line.amountCents() > 0 ? TransactionType.INCOME : TransactionType.EXPENSE).name())
					.param("m" + i, line.amountCents())
					.param("d" + i, line.occurredOn())
					.param("s" + i, line.description())
					.param("k" + i, line.key());
		}
		return statement.query(UUID.class).list();
	}

	/** Os lançamentos criados, na ordem em que foram inseridos. */
	private List<TransactionResponse> load(UUID workspaceId, List<UUID> ids) {
		if (ids.isEmpty()) {
			return List.of();
		}
		Map<UUID, TransactionResponse> byId = new HashMap<>();
		for (TransactionResponse row : jdbc.sql(TransactionService.SELECT + "  and t.id in (:ids)\n")
				.param("workspaceId", workspaceId)
				.param("ids", ids)
				.query(TransactionService.ROW_MAPPER)
				.list()) {
			byId.put(row.id(), row);
		}
		return ids.stream().map(byId::get).toList();
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
