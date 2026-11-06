package com.tostao.tostao_api.transaction;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.category.CategoryKind;
import com.tostao.tostao_api.common.error.AccountNotFoundException;
import com.tostao.tostao_api.common.error.CategoryNotFoundException;
import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.common.error.TransactionConflictException;
import com.tostao.tostao_api.common.error.TransactionNotFoundException;
import com.tostao.tostao_api.transaction.dto.CreateTransactionRequest;
import com.tostao.tostao_api.transaction.dto.TransactionResponse;
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
	private static final String SELECT = """
			select t.id, t.account_id, t.type, t.amount_cents, t.occurred_on, t.description, t.notes,
			       t.category_id, t.transfer_group_id, o.account_id as counterpart_account_id, t.created_by,
			       t.created_at, t.updated_at
			from transactions t
			left join transactions o
			       on o.workspace_id = t.workspace_id and o.transfer_group_id = t.transfer_group_id
			      and o.id <> t.id
			where t.workspace_id = :workspaceId and t.deleted_at is null
			""";

	private static final RowMapper<TransactionResponse> ROW_MAPPER = (rs, i) -> new TransactionResponse(
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
			rs.getObject("updated_at", OffsetDateTime.class).toInstant());

	private final JdbcClient jdbc;

	public TransactionService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Cria uma receita, uma despesa ou uma transferência. Tudo é checado antes de gravar: nenhuma exceção é
	 * capturada dentro da transação.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public TransactionResponse create(UUID workspaceId, CreateTransactionRequest request) {
		TransactionRules.requireDateInRange(request.occurredOn());
		if (request.type() == TransactionType.TRANSFER) {
			return createTransfer(workspaceId, request);
		}

		if (request.destinationAccountId() != null) {
			throw new FieldValidationException("destinationAccountId", "só vale para transferência");
		}
		if (request.categoryId() == null) {
			throw new FieldValidationException("categoryId", "obrigatório para receita e despesa");
		}
		requireUsableAccount(workspaceId, request.accountId());
		requireUsableCategory(workspaceId, request.categoryId(), request.type());

		UUID id = insertRow(workspaceId, request.accountId(), request.categoryId(), request.type(),
				TransactionRules.signedAmount(request.type(), request.amountCents()), request, null);
		return load(workspaceId, id);
	}

	/**
	 * Transferência: duas pontas ligadas por um grupo, gravadas na mesma transação. As duas contas são
	 * travadas em ordem de id (evita impasse entre transferências opostas) e conferidas antes de gravar.
	 */
	private TransactionResponse createTransfer(UUID workspaceId, CreateTransactionRequest request) {
		if (request.destinationAccountId() == null) {
			throw new FieldValidationException("destinationAccountId", "obrigatório para transferência");
		}
		if (request.destinationAccountId().equals(request.accountId())) {
			throw new FieldValidationException("destinationAccountId", "deve ser diferente da conta de origem");
		}
		if (request.categoryId() != null) {
			throw new FieldValidationException("categoryId", "transferência não tem categoria");
		}
		requireUsableAccounts(workspaceId, request.accountId(), request.destinationAccountId());

		UUID group = UUID.randomUUID();
		long[] legs = TransactionRules.transferLegs(request.amountCents());
		UUID outflow = insertRow(workspaceId, request.accountId(), null, TransactionType.TRANSFER, legs[0], request,
				group);
		insertRow(workspaceId, request.destinationAccountId(), null, TransactionType.TRANSFER, legs[1], request,
				group);
		return load(workspaceId, outflow);
	}

	private UUID insertRow(UUID workspaceId, UUID accountId, UUID categoryId, TransactionType type, long signedCents,
			CreateTransactionRequest request, UUID transferGroupId) {
		return jdbc.sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, notes, transfer_group_id, created_by)
				values (:workspaceId, :accountId, :categoryId, :type, :amount, :occurredOn, :description, :notes,
				        :transferGroupId, :createdBy)
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
				.param("createdBy", CurrentUser.id())
				.query(UUID.class)
				.single();
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

	/**
	 * A conta existe no workspace e está ativa. A linha fica travada em modo compartilhado: arquivar a conta
	 * (um {@code UPDATE}) espera este lançamento confirmar, e o arquivamento que chegar antes é visto aqui.
	 */
	private void requireUsableAccount(UUID workspaceId, UUID accountId) {
		boolean archived = jdbc.sql("""
				select archived from accounts
				where id = :accountId and workspace_id = :workspaceId
				for share
				""")
				.param("accountId", accountId)
				.param("workspaceId", workspaceId)
				.query(Boolean.class)
				.optional()
				.orElseThrow(AccountNotFoundException::new);
		if (archived) {
			throw new TransactionConflictException("A conta está arquivada");
		}
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

	/** A categoria existe no workspace, está ativa e tem o tipo do lançamento. Também travada ({@code FOR SHARE}). */
	private void requireUsableCategory(UUID workspaceId, UUID categoryId, TransactionType type) {
		record Category(CategoryKind kind, boolean archived) {
		}
		Category category = jdbc.sql("""
				select kind, archived from categories
				where id = :categoryId and workspace_id = :workspaceId
				for share
				""")
				.param("categoryId", categoryId)
				.param("workspaceId", workspaceId)
				.query((rs, i) -> new Category(CategoryKind.valueOf(rs.getString("kind")), rs.getBoolean("archived")))
				.optional()
				.orElseThrow(CategoryNotFoundException::new);
		TransactionRules.requireCategoryKind(type, category.kind());
		if (category.archived()) {
			throw new TransactionConflictException("A categoria está arquivada");
		}
	}

}
