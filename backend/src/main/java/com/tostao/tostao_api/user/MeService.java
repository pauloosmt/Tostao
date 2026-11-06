package com.tostao.tostao_api.user;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.common.error.UnauthenticatedException;
import com.tostao.tostao_api.workspace.TenantSession;
import com.tostao.tostao_api.workspace.WorkspaceMembershipResponse;

@Service
public class MeService {

	private final JdbcClient jdbc;
	private final TenantSession tenant;

	public MeService(JdbcClient jdbc, TenantSession tenant) {
		this.jdbc = jdbc;
		this.tenant = tenant;
	}

	/** Lê o usuário e seus workspaces. Pelo RLS, só as linhas do próprio usuário são visíveis. */
	@Transactional(readOnly = true)
	public MeResponse load(UUID userId) {
		tenant.enterUser(userId);

		var user = jdbc.sql("select id, name, email from users where id = :id")
				.param("id", userId)
				.query((rs, i) -> new UserRow(rs.getObject("id", UUID.class), rs.getString("name"),
						rs.getString("email")))
				.optional()
				.orElseThrow(UnauthenticatedException::new);

		List<WorkspaceMembershipResponse> workspaces = jdbc.sql("""
				select w.id, w.name, w.base_currency, m.role
				from workspaces w
				join workspace_members m on m.workspace_id = w.id
				where m.user_id = :userId
				order by w.created_at, w.id
				""")
				.param("userId", userId)
				.query((rs, i) -> new WorkspaceMembershipResponse(rs.getObject("id", UUID.class),
						rs.getString("name"), rs.getString("base_currency"), rs.getString("role")))
				.list();

		return new MeResponse(user.id(), user.name(), user.email(), workspaces);
	}

	private record UserRow(UUID id, String name, String email) {
	}

}
