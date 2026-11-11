package com.tostao.tostao_api.transaction;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.tostao.tostao_api.category.CategoryKind;
import com.tostao.tostao_api.common.error.AccountNotFoundException;
import com.tostao.tostao_api.common.error.CategoryNotFoundException;
import com.tostao.tostao_api.common.error.TransactionConflictException;

/**
 * Confere e trava a conta e a categoria que um lançamento vai usar, para nunca nascer lançamento em conta ou
 * categoria arquivada, nem sob corrida com o arquivamento. É usada pela criação de lançamentos e pela compra
 * parcelada, para as duas terem exatamente a mesma regra. Não é um bean: cada serviço a cria a partir do seu
 * {@link JdbcClient}, e as consultas rodam na transação do serviço que a chama.
 */
public final class UsableRefs {

	private final JdbcClient jdbc;

	public UsableRefs(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * A conta existe no workspace e está ativa. A linha fica travada em modo compartilhado: arquivar a conta
	 * (um {@code UPDATE}) espera este lançamento confirmar, e o arquivamento que chegar antes é visto aqui.
	 */
	public void requireUsableAccount(UUID workspaceId, UUID accountId) {
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
	public void requireUsableAccounts(UUID workspaceId, UUID first, UUID second) {
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
	public void requireUsableCategory(UUID workspaceId, UUID categoryId, TransactionType type) {
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
