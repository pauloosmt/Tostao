package com.tostao.tostao_api.membership;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.common.error.InsufficientRoleException;
import com.tostao.tostao_api.common.error.MemberNotFoundException;
import com.tostao.tostao_api.common.error.MembershipConflictException;
import com.tostao.tostao_api.membership.dto.ChangeRoleRequest;
import com.tostao.tostao_api.membership.dto.MemberResponse;
import com.tostao.tostao_api.workspace.CurrentUser;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/**
 * Membros de um workspace: listar, mudar o papel e remover (ou sair). Duas camadas de isolamento:
 * {@link WorkspaceScoped} (participação e papel) e o RLS cuidam do workspace, e toda consulta daqui também filtra
 * explicitamente por {@code workspace_id}. As regras de quem pode o quê estão aqui, com as mensagens certas, e as
 * políticas da {@code V12} as repetem no banco como rede de proteção. A autorização é lida do banco a cada requisição,
 * então mudar o papel, remover e sair valem na próxima requisição da pessoa afetada.
 */
@Service
public class MemberService {

	private static final String SELECT = """
			select m.user_id, u.name, u.email, m.role, m.created_at
			from workspace_members m
			join users u on u.id = m.user_id
			where m.workspace_id = :workspaceId
			""";

	private static final RowMapper<MemberResponse> MAPPER = (rs, i) -> new MemberResponse(
			rs.getObject("user_id", UUID.class),
			rs.getString("name"),
			rs.getString("email"),
			rs.getString("role"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private final JdbcClient jdbc;

	public MemberService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Os membros do workspace: o dono primeiro e depois por data de entrada. Qualquer membro pode listar. */
	@WorkspaceScoped
	@Transactional(readOnly = true)
	public List<MemberResponse> list(UUID workspaceId) {
		return jdbc.sql(SELECT + "order by (m.role = 'OWNER') desc, m.created_at, m.user_id\n")
				.param("workspaceId", workspaceId)
				.query(MAPPER)
				.list();
	}

	/**
	 * Muda o papel de um membro entre EDITOR e VIEWER (só o dono). O papel pedido é conferido antes de gravar; mexer no
	 * dono é conflito; pedir o papel que o membro já tem responde com o membro, sem efeito.
	 */
	@WorkspaceScoped(owner = true)
	@Transactional
	public MemberResponse changeRole(UUID workspaceId, UUID userId, ChangeRoleRequest request) {
		String role = request.role();
		if (!"EDITOR".equals(role) && !"VIEWER".equals(role)) {
			throw new FieldValidationException("role", "deve ser EDITOR ou VIEWER");
		}
		String current = roleOf(workspaceId, userId).orElseThrow(MemberNotFoundException::new);
		if ("OWNER".equals(current)) {
			// também cobre o dono mudando o próprio papel: o único dono é ele
			throw new MembershipConflictException("O papel do dono não pode ser mudado");
		}
		if (!role.equals(current)) {
			int changed = jdbc.sql("""
					update workspace_members set role = :role
					where workspace_id = :workspaceId and user_id = :userId and role <> 'OWNER'
					""")
					.param("role", role)
					.param("workspaceId", workspaceId)
					.param("userId", userId)
					.update();
			if (changed == 0) {
				throw new MemberNotFoundException();
			}
		}
		return jdbc.sql(SELECT + "  and m.user_id = :userId\n")
				.param("workspaceId", workspaceId)
				.param("userId", userId)
				.query(MAPPER)
				.optional()
				.orElseThrow(MemberNotFoundException::new);
	}

	/**
	 * Remove um membro (o dono remove qualquer um que não é dono) ou faz a pessoa sair (qualquer membro que não é dono
	 * remove o próprio vínculo). Quem não é dono removendo outra pessoa é 403; remover o dono, ou o dono sair, é conflito.
	 * Os dados que a pessoa criou continuam no workspace, com o {@code created_by} intacto.
	 */
	@WorkspaceScoped
	@Transactional
	public void remove(UUID workspaceId, UUID userId) {
		UUID caller = CurrentUser.id();
		String target = roleOf(workspaceId, userId).orElseThrow(MemberNotFoundException::new);
		String callerRole = roleOf(workspaceId, caller).orElseThrow(MemberNotFoundException::new);
		if (!"OWNER".equals(callerRole) && !caller.equals(userId)) {
			throw new InsufficientRoleException();
		}
		if ("OWNER".equals(target)) {
			throw new MembershipConflictException("O dono não pode ser removido nem sair do workspace");
		}
		int removed = jdbc.sql("""
				delete from workspace_members
				where workspace_id = :workspaceId and user_id = :userId and role <> 'OWNER'
				""")
				.param("workspaceId", workspaceId)
				.param("userId", userId)
				.update();
		if (removed == 0) {
			throw new MemberNotFoundException();
		}
	}

	/** O papel de um membro do workspace, se for membro. */
	private Optional<String> roleOf(UUID workspaceId, UUID userId) {
		return jdbc.sql("select role from workspace_members where workspace_id = :workspaceId and user_id = :userId")
				.param("workspaceId", workspaceId)
				.param("userId", userId)
				.query(String.class)
				.optional();
	}

}
