package com.tostao.tostao_api.workspace;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Define, para a transação em curso, quem é o usuário e qual é o workspace da requisição. As
 * políticas de RLS leem esses valores. {@code set_config(..., true)} limita o valor à transação
 * (equivale a {@code SET LOCAL}), então nada vaza para outra requisição que reaproveite a conexão.
 */
@Component
public class TenantSession {

	private final JdbcClient jdbc;

	public TenantSession(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Define só o usuário: permite ver os próprios dados e as próprias associações. */
	public void enterUser(UUID userId) {
		requireTransaction();
		jdbc.sql("select set_config('app.user_id', :user, true)")
				.param("user", userId.toString())
				.query(String.class).single();
	}

	/** Define usuário e workspace. Só deve ser chamado depois de confirmar que o usuário é membro. */
	public void enterWorkspace(UUID userId, UUID workspaceId) {
		requireTransaction();
		jdbc.sql("select set_config('app.user_id', :user, true), set_config('app.workspace_id', :workspace, true)")
				.param("user", userId.toString())
				.param("workspace", workspaceId.toString())
				.query((rs, i) -> rs.getString(1)).single();
	}

	private static void requireTransaction() {
		if (!TransactionSynchronizationManager.isActualTransactionActive()) {
			throw new IllegalStateException("O contexto de workspace só pode ser definido dentro de uma transação");
		}
	}

}
