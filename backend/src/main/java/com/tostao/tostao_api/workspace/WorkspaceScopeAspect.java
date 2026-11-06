package com.tostao.tostao_api.workspace;

import java.util.UUID;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.tostao.tostao_api.common.error.UnauthenticatedException;
import com.tostao.tostao_api.common.error.WorkspaceNotFoundException;

/**
 * Aplica a regra de participação no workspace. Roda <b>dentro</b> da transação do serviço (ordem
 * menos prioritária que a do advice transacional, ver {@code TransactionConfig}): confere a
 * associação do usuário, responde 404 se não houver, e só então define o contexto de workspace do
 * RLS. O usuário vem sempre do {@code SecurityContext}, nunca de argumento do método.
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

	@Around("@annotation(com.tostao.tostao_api.workspace.WorkspaceScoped) && args(workspaceId, ..)")
	public Object enterWorkspace(ProceedingJoinPoint joinPoint, UUID workspaceId) throws Throwable {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken jwt)) {
			throw new UnauthenticatedException();
		}
		UUID userId = UUID.fromString(jwt.getToken().getSubject());

		tenant.enterUser(userId);
		boolean member = Boolean.TRUE.equals(jdbc.sql("""
				select exists (
				    select 1 from workspace_members
				    where workspace_id = :workspaceId and user_id = :userId)
				""")
				.param("workspaceId", workspaceId)
				.param("userId", userId)
				.query(Boolean.class)
				.single());
		if (!member) {
			// workspace inexistente e workspace alheio são indistinguíveis
			throw new WorkspaceNotFoundException();
		}

		tenant.enterWorkspace(userId, workspaceId);
		return joinPoint.proceed();
	}

}
