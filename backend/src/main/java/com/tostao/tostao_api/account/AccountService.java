package com.tostao.tostao_api.account;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.account.dto.AccountResponse;
import com.tostao.tostao_api.account.dto.CreateAccountRequest;
import com.tostao.tostao_api.common.error.AccountNameAlreadyExistsException;
import com.tostao.tostao_api.common.error.AccountNotFoundException;
import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/**
 * Regras de contas. Duas camadas de isolamento: {@link WorkspaceScoped} (participação e papel) e o
 * RLS cuidam do workspace, e toda consulta daqui também filtra explicitamente por {@code workspace_id}.
 * O saldo é calculado a cada consulta e nunca é guardado.
 */
@Service
public class AccountService {

	/**
	 * Conta com o saldo calculado: saldo inicial mais os lançamentos não excluídos da conta. A soma de
	 * {@code bigint} no Postgres é {@code numeric}; o resultado volta a {@code bigint}.
	 */
	private static final String SELECT_WITH_BALANCE = """
			select a.id, a.name, a.type, a.currency, a.initial_balance_cents, a.credit_limit_cents,
			       a.closing_day, a.due_day, a.archived, a.created_at, a.updated_at,
			       (a.initial_balance_cents + coalesce(sum(t.amount_cents), 0))::bigint as balance_cents
			from accounts a
			left join transactions t
			       on t.account_id = a.id and t.workspace_id = a.workspace_id and t.deleted_at is null
			where a.workspace_id = :workspaceId
			""";

	private static final String GROUP_AND_ORDER = """
			group by a.id
			order by lower(a.name), a.id
			""";

	private static final RowMapper<AccountResponse> ROW_MAPPER = (rs, i) -> new AccountResponse(
			rs.getObject("id", UUID.class),
			rs.getString("name"),
			AccountType.valueOf(rs.getString("type")),
			rs.getString("currency").strip(),
			rs.getLong("initial_balance_cents"),
			rs.getLong("balance_cents"),
			rs.getObject("credit_limit_cents", Long.class),
			rs.getObject("closing_day", Integer.class),
			rs.getObject("due_day", Integer.class),
			rs.getBoolean("archived"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant(),
			rs.getObject("updated_at", OffsetDateTime.class).toInstant());

	private final JdbcClient jdbc;

	public AccountService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Cria uma conta. O nome repetido entre contas ativas é decidido pela própria inserção
	 * ({@code ON CONFLICT DO NOTHING}), sem exceção dentro da transação.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public AccountResponse create(UUID workspaceId, CreateAccountRequest request) {
		requireCardFieldsOnlyOnCards(request.type(), request.creditLimitCents(), request.closingDay(),
				request.dueDay());

		UUID id = jdbc.sql("""
				insert into accounts (workspace_id, name, type, initial_balance_cents, credit_limit_cents,
				                      closing_day, due_day)
				values (:workspaceId, :name, :type, :initial, cast(:limit as bigint), cast(:closing as smallint),
				        cast(:due as smallint))
				on conflict (workspace_id, lower(name)) where not archived do nothing
				returning id
				""")
				.param("workspaceId", workspaceId)
				.param("name", request.name())
				.param("type", request.type().name())
				.param("initial", request.initialBalanceCents())
				.param("limit", request.creditLimitCents())
				.param("closing", request.closingDay())
				.param("due", request.dueDay())
				.query(UUID.class)
				.optional()
				.orElseThrow(AccountNameAlreadyExistsException::new);

		return load(workspaceId, id);
	}

	/** Contas do workspace com o saldo de cada uma, por nome. Por padrão só as ativas. */
	@WorkspaceScoped
	@Transactional(readOnly = true)
	public List<AccountResponse> list(UUID workspaceId, boolean includeArchived) {
		String sql = SELECT_WITH_BALANCE + (includeArchived ? "" : "  and not a.archived\n") + GROUP_AND_ORDER;
		return jdbc.sql(sql).param("workspaceId", workspaceId).query(ROW_MAPPER).list();
	}

	/** Uma conta com o saldo calculado. Conta de outro workspace, ou inexistente, é "não encontrada". */
	@WorkspaceScoped
	@Transactional(readOnly = true)
	public AccountResponse get(UUID workspaceId, UUID accountId) {
		return load(workspaceId, accountId);
	}

	/** Carrega uma conta do workspace; a conta de outro workspace é "não encontrada". */
	private AccountResponse load(UUID workspaceId, UUID accountId) {
		return jdbc.sql(SELECT_WITH_BALANCE + "  and a.id = :accountId\n" + GROUP_AND_ORDER)
				.param("workspaceId", workspaceId)
				.param("accountId", accountId)
				.query(ROW_MAPPER)
				.optional()
				.orElseThrow(AccountNotFoundException::new);
	}

	/** Limite e dias de fechamento e vencimento só existem em cartão de crédito. */
	private static void requireCardFieldsOnlyOnCards(AccountType type, Long creditLimitCents, Integer closingDay,
			Integer dueDay) {
		if (type.hasCardFields()) {
			return;
		}
		if (creditLimitCents != null) {
			throw new FieldValidationException("creditLimitCents", "só vale para cartão de crédito");
		}
		if (closingDay != null) {
			throw new FieldValidationException("closingDay", "só vale para cartão de crédito");
		}
		if (dueDay != null) {
			throw new FieldValidationException("dueDay", "só vale para cartão de crédito");
		}
	}

}
