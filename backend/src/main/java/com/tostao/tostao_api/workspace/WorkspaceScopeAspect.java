package com.tostao.tostao_api.workspace;

import java.util.UUID;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.tostao.tostao_api.common.error.InsufficientRoleException;
import com.tostao.tostao_api.common.error.WorkspaceNotFoundException;

/**
 * Aplica a regra de participação no workspace. Roda <b>dentro</b> da transação do serviço (ordem
 * menos prioritária que a do advice transacional, ver {@code TransactionConfig}): confere a
 * associação do usuário, responde 404 se não houver, responde 403 se a operação é de escrita e o papel
 * é VIEWER (ou se é só do dono e o papel não é OWNER), e só então define o contexto de workspace do RLS. O usuário vem sempre do {@code SecurityContext}, nunca de argumento do método.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class WorkspaceScopeAspect {

	private final TenantSession tenant;
	private final JdbcClient jdbc;

	public WorkspaceScopeAspect(TenantSession tenant, JdbcClient jdbc) {
		this.tenant = tenant;
		this.jdbc = jdbc;
	}

	@Around("@annotation(scoped) && args(workspaceId, ..)")
	public Object enterWorkspace(ProceedingJoinPoint joinPoint, WorkspaceScoped scoped, UUID workspaceId)
			throws Throwable {
		UUID userId = CurrentUser.id();

		tenant.enterUser(userId);
		String role = jdbc.sql("""
				select role from workspace_members
				where workspace_id = :workspaceId and user_id = :userId
				""")
				.param("workspaceId", workspaceId)
				.param("userId", userId)
				.query(String.class)
				.optional()
				// workspace inexistente e workspace alheio são indistinguíveis
				.orElseThrow(WorkspaceNotFoundException::new);
		if (scoped.write() && !("OWNER".equals(role) || "EDITOR".equals(role))) {
			throw new InsufficientRoleException();
		}
		if (scoped.owner() && !"OWNER".equals(role)) {
			throw new InsufficientRoleException();
		}

		tenant.enterWorkspace(userId, workspaceId);
		return joinPoint.proceed();
	}

}
