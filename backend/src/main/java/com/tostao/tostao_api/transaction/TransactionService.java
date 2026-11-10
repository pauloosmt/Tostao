package com.tostao.tostao_api.transaction;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.common.error.AccountNotFoundException;
import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.common.error.TransactionConflictException;
import com.tostao.tostao_api.common.error.TransactionNotFoundException;
import com.tostao.tostao_api.transaction.dto.CreateTransactionRequest;
import com.tostao.tostao_api.transaction.dto.TransactionPageResponse;
import com.tostao.tostao_api.transaction.dto.TransactionResponse;
import com.tostao.tostao_api.transaction.dto.UpdateTransactionRequest;
import com.tostao.tostao_api.workspace.CurrentUser;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/**
 * Regras de lançamentos. Duas camadas de isolamento: {@link WorkspaceScoped} (participação e papel) e o
 * RLS cuidam do workspace, e toda consulta daqui também filtra explicitamente por {@code workspace_id}.
 * Contas e categorias são conferidas e travadas ({@code FOR SHARE}) antes de gravar, para nunca nascer
 * lançamento em conta ou categoria arquivada, nem sob corrida com o arquivamento.
 */
@Service
public class TransactionService {

	/** Lançamento vigente (não excluído) do workspace, com a conta da outra ponta se for transferência. */
	public static final String SELECT = """
			select t.id, t.account_id, t.type, t.amount_cents, t.occurred_on, t.description, t.notes,
			       t.category_id, t.transfer_group_id, o.account_id as counterpart_account_id, t.created_by,
			       t.created_at, t.updated_at, t.status,
			       t.installment_group_id, t.installment_number, t.installment_count,
			       t.auto_clear, t.recurrence_id, t.occurrence_number, (t.import_key is not null) as imported
			from transactions t
			left join transactions o
			       on o.workspace_id = t.workspace_id and o.transfer_group_id = t.transfer_group_id
			      and o.id <> t.id
			where t.workspace_id = :workspaceId and t.deleted_at is null
			""";

	public static final RowMapper<TransactionResponse> ROW_MAPPER = (rs, i) -> new TransactionResponse(
			rs.getObject("id", UUID.class),
			rs.getObject("account_id", UUID.class),
			TransactionType.valueOf(rs.getString("type")),
			rs.getLong("amount_cents"),
			rs.getObject("occurred_on", LocalDate.class),
			rs.getString("description"),
			rs.getString("notes"),
			rs.getObject("category_id", UUID.class),
			rs.getObject("transfer_group_id", UUID.class),
			rs.getObject("counterpart_account_id", UUID.class),
			rs.getObject("created_by", UUID.class),
			rs.getObject("created_at", OffsetDateTime.class).toInstant(),
			rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
			TransactionStatus.valueOf(rs.getString("status")),
			rs.getObject("installment_group_id", UUID.class),
			rs.getObject("installment_number", Integer.class),
			rs.getObject("installment_count", Integer.class),
			rs.getBoolean("auto_clear"),
			rs.getObject("recurrence_id", UUID.class),
			rs.getObject("occurrence_number", Integer.class),
			rs.getBoolean("imported"));

	private final JdbcClient jdbc;
	private final UsableRefs refs;

	public TransactionService(JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.refs = new UsableRefs(jdbc);
	}

	/**
	 * Cria uma receita, uma despesa ou uma transferência. Tudo é checado antes de gravar: nenhuma exceção é
	 * capturada dentro da transação.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public TransactionResponse create(UUID workspaceId, CreateTransactionRequest request) {
		TransactionRules.requireDateInRange(request.occurredOn());
		TransactionStatus status = TransactionRules.parseStatus(request.status());
		// sem estado informado o lançamento nasce efetivado, e a efetivação automática só vale para pendente
		TransactionRules.requireAutoClearPending(request.autoClear(), status == null ? TransactionStatus.CLEARED : status);
		if (request.type() == TransactionType.TRANSFER) {
			return createTransfer(workspaceId, request, status);
		}

		if (request.destinationAccountId() != null) {
			throw new FieldValidationException("destinationAccountId", "só vale para transferência");
		}
		if (request.categoryId() == null) {
			throw new FieldValidationException("categoryId", "obrigatório para receita e despesa");
		}
		refs.requireUsableAccount(workspaceId, request.accountId());
		refs.requireUsableCategory(workspaceId, request.categoryId(), request.type());

		UUID id = insertRow(workspaceId, request.accountId(), request.categoryId(), request.type(),
				TransactionRules.signedAmount(request.type(), request.amountCents()), request, null, status);
		return load(workspaceId, id);
	}

	/**
	 * Transferência: duas pontas ligadas por um grupo, gravadas na mesma transação. As duas contas são
	 * travadas em ordem de id (evita impasse entre transferências opostas) e conferidas antes de gravar.
	 */
	private TransactionResponse createTransfer(UUID workspaceId, CreateTransactionRequest request,
			TransactionStatus status) {
		requireTransferFields(request.accountId(), request.destinationAccountId(), request.categoryId());
		requireUsableAccounts(workspaceId, request.accountId(), request.destinationAccountId());

		UUID group = UUID.randomUUID();
		long[] legs = TransactionRules.transferLegs(request.amountCents());
		UUID outflow = insertRow(workspaceId, request.accountId(), null, TransactionType.TRANSFER, legs[0], request,
				group, status);
		insertRow(workspaceId, request.destinationAccountId(), null, TransactionType.TRANSFER, legs[1], request,
				group, status);
		return load(workspaceId, outflow);
	}

	private UUID insertRow(UUID workspaceId, UUID accountId, UUID categoryId, TransactionType type, long signedCents,
			CreateTransactionRequest request, UUID transferGroupId, TransactionStatus status) {
		return jdbc.sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, notes, transfer_group_id, status, auto_clear, created_by)
				values (:workspaceId, :accountId, :categoryId, :type, :amount, :occurredOn, :description, :notes,
				        :transferGroupId, :status, :autoClear, :createdBy)
				returning id
				""")
				.param("workspaceId", workspaceId)
				.param("accountId", accountId)
				.param("categoryId", categoryId)
				.param("type", type.name())
				.param("amount", signedCents)
				.param("occurredOn", request.occurredOn())
				.param("description", request.description())
				.param("notes", request.notes())
				.param("transferGroupId", transferGroupId)
				// sem estado informado, o lançamento é efetivado, como antes desta funcionalidade
				.param("status", (status == null ? TransactionStatus.CLEARED : status).name())
				// as duas pontas de uma transferência recebem o mesmo marcador
				.param("autoClear", Boolean.TRUE.equals(request.autoClear()))
				.param("createdBy", CurrentUser.id())
				.query(UUID.class)
				.single();
	}

	/**
	 * Edita um lançamento. O tipo nunca muda. Numa transferência as duas pontas mudam juntas, na mesma
	 * transação: {@code accountId} é a origem e {@code destinationAccountId} o destino, qualquer que seja a
	 * ponta endereçada. Só as contas e a categoria que <b>mudaram</b> são conferidas e travadas: manter a conta
	 * ou a categoria arquivada que o lançamento já tinha é aceito, passar a usar uma arquivada não.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public TransactionResponse update(UUID workspaceId, UUID transactionId, UpdateTransactionRequest request) {
		TransactionRules.requireDateInRange(request.occurredOn());
		TransactionStatus status = TransactionRules.parseStatus(request.status());
		Peek peek = peek(workspaceId, transactionId);
		// o estado resultante é o pedido ou, ausente, o atual; ligar o marcador num que resulta efetivado é recusado
		TransactionRules.requireAutoClearPending(request.autoClear(), status == null ? peek.status() : status);
		if (request.type() != null && request.type() != peek.type()) {
			throw new FieldValidationException("type", "não pode ser mudado");
		}
		if (peek.type() == TransactionType.TRANSFER) {
			requireTransferFields(request.accountId(), request.destinationAccountId(), request.categoryId());
		}
		else {
			if (request.destinationAccountId() != null) {
				throw new FieldValidationException("destinationAccountId", "só vale para transferência");
			}
			if (request.categoryId() == null) {
				throw new FieldValidationException("categoryId", "obrigatório para receita e despesa");
			}
		}

		List<Row> rows = lockUnit(workspaceId, transactionId, peek);
		if (peek.type() == TransactionType.TRANSFER) {
			if (rows.size() != 2) {
				throw new TransactionNotFoundException();
			}
			Row outflow = rows.stream().filter(r -> r.amountCents() < 0).findFirst().orElseThrow();
			Row inflow = rows.stream().filter(r -> r.amountCents() > 0).findFirst().orElseThrow();
			List<UUID> changed = new ArrayList<>();
			if (!request.accountId().equals(outflow.accountId())) {
				changed.add(request.accountId());
			}
			if (!request.destinationAccountId().equals(inflow.accountId())) {
				changed.add(request.destinationAccountId());
			}
			if (changed.size() == 1) {
				refs.requireUsableAccount(workspaceId, changed.get(0));
			}
			else if (changed.size() == 2) {
				requireUsableAccounts(workspaceId, changed.get(0), changed.get(1));
			}

			long[] legs = TransactionRules.transferLegs(request.amountCents());
			updateRow(workspaceId, outflow.id(), request.accountId(), outflow.categoryId(), legs[0], request, status);
			updateRow(workspaceId, inflow.id(), request.destinationAccountId(), inflow.categoryId(), legs[1],
					request, status);
		}
		else {
			Row row = rows.get(0);
			if (!request.accountId().equals(row.accountId())) {
				refs.requireUsableAccount(workspaceId, request.accountId());
			}
			if (!request.categoryId().equals(row.categoryId())) {
				refs.requireUsableCategory(workspaceId, request.categoryId(), peek.type());
			}
			updateRow(workspaceId, row.id(), request.accountId(), request.categoryId(),
					TransactionRules.signedAmount(peek.type(), request.amountCents()), request, status);
		}
		return load(workspaceId, transactionId);
	}

	/** Contas obrigatórias, distintas, e sem categoria: as regras de campo de uma transferência. */
	private static void requireTransferFields(UUID accountId, UUID destinationAccountId, UUID categoryId) {
		if (destinationAccountId == null) {
			throw new FieldValidationException("destinationAccountId", "obrigatório para transferência");
		}
		if (destinationAccountId.equals(accountId)) {
			throw new FieldValidationException("destinationAccountId", "deve ser diferente da conta de origem");
		}
		if (categoryId != null) {
			throw new FieldValidationException("categoryId", "transferência não tem categoria");
		}
	}

	private void updateRow(UUID workspaceId, UUID id, UUID accountId, UUID categoryId, long signedCents,
			UpdateTransactionRequest request, TransactionStatus status) {
		jdbc.sql("""
				update transactions
				set account_id = :accountId, category_id = :categoryId, amount_cents = :amount,
				    occurred_on = :occurredOn, description = :description, notes = :notes,
				    status = coalesce(cast(:status as text), status),
				    auto_clear = case when coalesce(cast(:status as text), status) = 'CLEARED' then false
				                      else coalesce(cast(:autoClear as boolean), auto_clear) end,
				    updated_at = now()
				where id = :id and workspace_id = :workspaceId and deleted_at is null
				""")
				.param("accountId", accountId)
				.param("categoryId", categoryId)
				.param("amount", signedCents)
				.param("occurredOn", request.occurredOn())
				.param("description", request.description())
				.param("notes", request.notes())
				// estado ausente mantém o atual (um pendente não vira efetivado por acaso)
				.param("status", status == null ? null : status.name())
				// marcador ausente mantém o atual; ao efetivar, o marcador sai desligado (uso único)
				.param("autoClear", request.autoClear())
				.param("id", id)
				.param("workspaceId", workspaceId)
				.update();
	}

	/**
	 * Exclusão lógica: põe {@code deleted_at} no lançamento ou, numa transferência, nas duas pontas. Nunca
	 * apaga a linha. Lançamento já excluído, de outro workspace ou inexistente é "não encontrado".
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public void delete(UUID workspaceId, UUID transactionId) {
		Peek peek = peek(workspaceId, transactionId);
		List<Row> rows = lockUnit(workspaceId, transactionId, peek);
		jdbc.sql("""
				update transactions set deleted_at = now(), updated_at = now()
				where workspace_id = :workspaceId and id in (:ids) and deleted_at is null
				""")
				.param("workspaceId", workspaceId)
				.param("ids", rows.stream().map(Row::id).toList())
				.update();
	}

	/**
	 * Efetiva um lançamento pendente (numa transferência, as duas pontas juntas). Idempotente: o {@code UPDATE} só
	 * toca linhas ainda pendentes, então efetivar de novo não altera nem {@code updated_at}. Não confere conta nem
	 * categoria ativas: efetivar não é passar a usá-las. A unidade é travada em ordem de id, como na edição e na
	 * exclusão, então duas efetivações (ou uma efetivação e uma exclusão) simultâneas nunca deixam as pontas em
	 * estados diferentes.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public TransactionResponse clear(UUID workspaceId, UUID transactionId) {
		Peek peek = peek(workspaceId, transactionId);
		List<Row> rows = lockUnit(workspaceId, transactionId, peek);
		jdbc.sql("""
				update transactions set status = 'CLEARED', auto_clear = false, updated_at = now()
				where workspace_id = :workspaceId and id in (:ids) and deleted_at is null and status <> 'CLEARED'
				""")
				.param("workspaceId", workspaceId)
				.param("ids", rows.stream().map(Row::id).toList())
				.update();
		return load(workspaceId, transactionId);
	}

	/** Linha de lançamento lida e travada para alteração. */
	private record Row(UUID id, UUID accountId, UUID categoryId, long amountCents) {
	}

	/** Tipo e grupo do lançamento, lidos sem travar para saber o que travar. */
	private record Peek(TransactionType type, UUID transferGroupId, TransactionStatus status) {
	}

	private Peek peek(UUID workspaceId, UUID transactionId) {
		return jdbc.sql("""
				select type, transfer_group_id, status from transactions
				where id = :id and workspace_id = :workspaceId and deleted_at is null
				""")
				.param("id", transactionId)
				.param("workspaceId", workspaceId)
				.query((rs, i) -> new Peek(TransactionType.valueOf(rs.getString("type")),
						rs.getObject("transfer_group_id", UUID.class), TransactionStatus.valueOf(rs.getString("status"))))
				.optional()
				.orElseThrow(TransactionNotFoundException::new);
	}

	/**
	 * Trava a unidade a alterar: a linha, ou as duas pontas da transferência, sempre em ordem de id e sempre
	 * pelo grupo (nunca pela ponta endereçada primeiro), para duas operações opostas na mesma transferência
	 * não se bloquearem uma à outra. Depois de esperar o bloqueio, a linha é relida: se foi excluída nesse
	 * meio tempo, é "não encontrada".
	 */
	private List<Row> lockUnit(UUID workspaceId, UUID transactionId, Peek peek) {
		String where = peek.transferGroupId() == null ? "id = :key" : "transfer_group_id = :key";
		List<Row> rows = jdbc.sql("""
				select id, account_id, category_id, amount_cents from transactions
				where workspace_id = :workspaceId and deleted_at is null and\s""" + where + """

				order by id
				for update
				""")
				.param("workspaceId", workspaceId)
				.param("key", peek.transferGroupId() == null ? transactionId : peek.transferGroupId())
				.query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
						rs.getObject("category_id", UUID.class), rs.getLong("amount_cents")))
				.list();
		if (rows.stream().noneMatch(r -> r.id().equals(transactionId))) {
			throw new TransactionNotFoundException();
		}
		return rows;
	}

	/**
	 * Uma página de lançamentos vigentes, do mais recente para o mais antigo, com os filtros combinados com
	 * "e". A página e a contagem saem da mesma leitura consistente ({@code REPEATABLE READ}), então o total
	 * e a página nunca discordam sob escrita simultânea. Texto, datas e ids vão sempre como parâmetros.
	 */
	@WorkspaceScoped
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public TransactionPageResponse list(UUID workspaceId, TransactionFilter filter) {
		if (filter.page() < 1) {
			throw new FieldValidationException("page", "deve ser 1 ou mais");
		}
		if (filter.size() < 1 || filter.size() > TransactionFilter.MAX_SIZE) {
			throw new FieldValidationException("size", "deve ser de 1 a " + TransactionFilter.MAX_SIZE);
		}
		if (filter.q() != null && filter.q().length() > TransactionFilter.MAX_TEXT) {
			throw new FieldValidationException("q", "deve ter no máximo " + TransactionFilter.MAX_TEXT + " caracteres");
		}
		if (filter.from() != null && filter.to() != null && filter.from().isAfter(filter.to())) {
			throw new FieldValidationException("from", "não pode ser depois da data final");
		}
		boolean uncategorized = parseUncategorized(filter.uncategorized());
		if (uncategorized && filter.categoryId() != null) {
			throw new FieldValidationException("uncategorized", "não pode ser combinado com categoryId");
		}

		StringBuilder conditions = new StringBuilder();
		Map<String, Object> params = new HashMap<>();
		params.put("workspaceId", workspaceId);
		if (uncategorized) {
			// receita e despesa sem categoria (a transferência nunca tem categoria e fica de fora)
			conditions.append("  and t.category_id is null and t.type <> 'TRANSFER'\n");
		}
		if (filter.from() != null) {
			conditions.append("  and t.occurred_on >= :from\n");
			params.put("from", filter.from());
		}
		if (filter.to() != null) {
			conditions.append("  and t.occurred_on <= :to\n");
			params.put("to", filter.to());
		}
		if (filter.accountId() != null) {
			conditions.append("  and t.account_id = :accountId\n");
			params.put("accountId", filter.accountId());
		}
		if (filter.categoryId() != null) {
			conditions.append("""
					  and (t.category_id = :categoryId
					       or t.category_id in (select c.id from categories c
					                            where c.parent_id = :categoryId and c.workspace_id = :workspaceId))
					""");
			params.put("categoryId", filter.categoryId());
		}
		if (filter.type() != null) {
			conditions.append("  and t.type = :type\n");
			params.put("type", filter.type().name());
		}
		if (filter.status() != null) {
			conditions.append("  and t.status = :status\n");
			params.put("status", filter.status().name());
		}
		if (filter.q() != null && !filter.q().isEmpty()) {
			conditions.append("  and lower(t.description) like lower(:pattern) escape '\\'\n");
			params.put("pattern", TransactionRules.likePattern(filter.q()));
		}

		long totalItems = jdbc.sql("""
				select count(*) from transactions t
				where t.workspace_id = :workspaceId and t.deleted_at is null
				""" + conditions)
				.params(params)
				.query(Long.class)
				.single();

		Map<String, Object> pageParams = new HashMap<>(params);
		pageParams.put("size", filter.size());
		pageParams.put("offset", (long) (filter.page() - 1) * filter.size());
		List<TransactionResponse> items = jdbc.sql(SELECT + conditions + """
				order by t.occurred_on desc, t.created_at desc, t.id desc
				limit :size offset :offset
				""")
				.params(pageParams)
				.query(ROW_MAPPER)
				.list();

		return new TransactionPageResponse(items, filter.page(), filter.size(), totalItems,
				TransactionRules.totalPages(totalItems, filter.size()));
	}

	/** Ausente é {@code false}; só {@code true} e {@code false} (em minúsculas) são aceitos. */
	private static boolean parseUncategorized(String raw) {
		if (raw == null || raw.equals("false")) {
			return false;
		}
		if (raw.equals("true")) {
			return true;
		}
		throw new FieldValidationException("uncategorized", "deve ser true ou false");
	}

	/** Um lançamento vigente do workspace. Excluído, de outro workspace ou inexistente é "não encontrado". */
	@WorkspaceScoped
	@Transactional(readOnly = true)
	public TransactionResponse get(UUID workspaceId, UUID transactionId) {
		return load(workspaceId, transactionId);
	}

	/** Um lançamento vigente do workspace. Excluído, de outro workspace ou inexistente é "não encontrado". */
	private TransactionResponse load(UUID workspaceId, UUID transactionId) {
		return jdbc.sql(SELECT + "  and t.id = :transactionId\n")
				.param("workspaceId", workspaceId)
				.param("transactionId", transactionId)
				.query(ROW_MAPPER)
				.optional()
				.orElseThrow(TransactionNotFoundException::new);
	}

	/** As duas contas existem no workspace e estão ativas; travadas em modo compartilhado, em ordem de id. */
	private void requireUsableAccounts(UUID workspaceId, UUID first, UUID second) {
		record Row(UUID id, boolean archived) {
		}
		List<Row> rows = jdbc.sql("""
				select id, archived from accounts
				where workspace_id = :workspaceId and id in (:ids)
				order by id
				for share
				""")
				.param("workspaceId", workspaceId)
				.param("ids", List.of(first, second))
				.query((rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getBoolean("archived")))
				.list();
		if (rows.size() != 2) {
			throw new AccountNotFoundException();
		}
		if (rows.stream().anyMatch(Row::archived)) {
			throw new TransactionConflictException("A conta está arquivada");
		}
	}

}
