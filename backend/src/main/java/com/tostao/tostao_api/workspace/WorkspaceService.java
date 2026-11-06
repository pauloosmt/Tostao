package com.tostao.tostao_api.workspace;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.common.error.WorkspaceNotFoundException;

@Service
public class WorkspaceService {

	private final JdbcClient jdbc;

	public WorkspaceService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Dados de um workspace de que o usuário é membro. Duas camadas: o {@link WorkspaceScoped} e o RLS
	 * isolam por workspace, e esta consulta também filtra explicitamente por workspace e por usuário,
	 * então o isolamento se mantém mesmo que uma das camadas falhe.
	 */
	@WorkspaceScoped
	@Transactional(readOnly = true)
	public WorkspaceMembershipResponse get(UUID workspaceId, UUID userId) {
		return jdbc.sql("""
				select w.id, w.name, w.base_currency, m.role
				from workspaces w
				join workspace_members m on m.workspace_id = w.id
				where w.id = :workspaceId
				  and m.workspace_id = :workspaceId
				  and m.user_id = :userId
				""")
				.param("workspaceId", workspaceId)
				.param("userId", userId)
				.query((rs, i) -> new WorkspaceMembershipResponse(rs.getObject("id", UUID.class),
						rs.getString("name"), rs.getString("base_currency"), rs.getString("role")))
				.optional()
				.orElseThrow(WorkspaceNotFoundException::new);
	}

}
