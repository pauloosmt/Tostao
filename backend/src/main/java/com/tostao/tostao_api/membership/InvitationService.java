package com.tostao.tostao_api.membership;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.common.error.FieldValidationException;
import com.tostao.tostao_api.common.error.InvalidInvitationException;
import com.tostao.tostao_api.common.error.InvitationNotFoundException;
import com.tostao.tostao_api.common.error.MembershipConflictException;
import com.tostao.tostao_api.membership.dto.AcceptInvitationResponse;
import com.tostao.tostao_api.membership.dto.CreateInvitationRequest;
import com.tostao.tostao_api.membership.dto.InvitationCreatedResponse;
import com.tostao.tostao_api.membership.dto.InvitationResponse;
import com.tostao.tostao_api.workspace.CurrentUser;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/**
 * Convites de um workspace: convidar, listar e revogar (só o dono). Duas camadas de isolamento:
 * {@link WorkspaceScoped} (participação e papel) e o RLS (que só mostra os convites ao OWNER) cuidam do workspace, e toda
 * consulta daqui também filtra explicitamente por {@code workspace_id}. O código nunca é guardado: só a impressão
 * SHA-256 dele, e ele volta uma única vez, na resposta de {@link #create}.
 */
@Service
public class InvitationService {

	static final int MAX_MEMBERS = 20;
	static final int MAX_PENDING = 20;

	private static final String PENDING = """
			select id, email, role, created_by, created_at, expires_at
			from invitations
			where workspace_id = :workspaceId and accepted_at is null and revoked_at is null and expires_at > now()
			""";

	private static final RowMapper<InvitationResponse> MAPPER = (rs, i) -> new InvitationResponse(
			rs.getObject("id", UUID.class),
			rs.getString("email"),
			rs.getString("role"),
			rs.getObject("created_by", UUID.class),
			rs.getObject("created_at", OffsetDateTime.class).toInstant(),
			rs.getObject("expires_at", OffsetDateTime.class).toInstant());

	private final JdbcClient jdbc;

	public InvitationService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * Convida uma pessoa por e-mail. Tudo é conferido antes de gravar, na ordem: o papel (400), quem já é membro (409),
	 * a substituição do convite pendente do mesmo e-mail e os limites de 20 membros e 20 convites pendentes (409).
	 * Nenhuma exceção é capturada: um conflito desfaz a transação inteira, inclusive a revogação do convite anterior. Uma
	 * trava consultiva por workspace serializa os convites do mesmo workspace, para dois convites simultâneos nunca
	 * ultrapassarem o limite. O código volta aqui, uma só vez.
	 */
	@WorkspaceScoped(owner = true)
	@Transactional
	public InvitationCreatedResponse create(UUID workspaceId, CreateInvitationRequest request) {
		String role = request.role();
		if (!"EDITOR".equals(role) && !"VIEWER".equals(role)) {
			throw new FieldValidationException("role", "deve ser EDITOR ou VIEWER");
		}

		jdbc.sql("select pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", workspaceId.toString())
				.query((rs, i) -> 1).single();

		boolean alreadyMember = jdbc.sql("""
				select exists (select 1 from workspace_members m join users u on u.id = m.user_id
				               where m.workspace_id = :workspaceId and lower(u.email) = :email)
				""")
				.param("workspaceId", workspaceId)
				.param("email", request.email())
				.query(Boolean.class)
				.single();
		if (alreadyMember) {
			throw new MembershipConflictException("Esta pessoa já participa do workspace");
		}

		// reconvidar invalida o convite pendente anterior do mesmo e-mail (inclusive o expirado)
		jdbc.sql("""
				update invitations set revoked_at = now()
				where workspace_id = :workspaceId and email = :email and accepted_at is null and revoked_at is null
				""")
				.param("workspaceId", workspaceId)
				.param("email", request.email())
				.update();

		long pending = jdbc.sql("select count(*) from (" + PENDING + ") p").param("workspaceId", workspaceId)
				.query(Long.class).single();
		if (pending >= MAX_PENDING) {
			throw new MembershipConflictException("O workspace atingiu o limite de convites pendentes");
		}
		long members = jdbc.sql("select count(*) from workspace_members where workspace_id = :workspaceId")
				.param("workspaceId", workspaceId).query(Long.class).single();
		if (members >= MAX_MEMBERS) {
			throw new MembershipConflictException("O workspace atingiu o limite de membros");
		}

		String code = InvitationCodes.generate();
		return jdbc.sql("""
				insert into invitations (workspace_id, email, role, code_hash, created_by, expires_at)
				values (:workspaceId, :email, :role, :codeHash, :createdBy, now() + interval '7 days')
				returning id, created_at, expires_at
				""")
				.param("workspaceId", workspaceId)
				.param("email", request.email())
				.param("role", role)
				.param("codeHash", InvitationCodes.hash(code))
				.param("createdBy", CurrentUser.id())
				.query((rs, i) -> new InvitationCreatedResponse(rs.getObject("id", UUID.class), request.email(), role,
						CurrentUser.id(), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
						rs.getObject("expires_at", OffsetDateTime.class).toInstant(), code))
				.single();
	}

	/**
	 * Aceita um convite com o código. Não fica sob um workspace: a pessoa ainda não é membro, e o RLS por workspace não a
	 * alcança. A função de banco {@code accept_invitation} (V13) confere o código pela impressão, a validade, o estado e o
	 * e-mail da <b>conta</b> (lido do banco a partir da sessão, nunca do corpo), trava o convite e o workspace, confere o
	 * limite de 20 membros e cria o vínculo, numa operação atômica. O resultado volta como desfecho, sem exceção: código
	 * inexistente, expirado, usado, revogado ou de outro e-mail respondem todos igual (404); já ser membro e o workspace
	 * cheio são conflito (409).
	 */
	@Transactional
	public AcceptInvitationResponse accept(String code) {
		record Outcome(String outcome, UUID workspaceId, String name, String role) {
		}
		Outcome result = jdbc.sql("select outcome, workspace_id, workspace_name, role from accept_invitation(:codeHash, :userId)")
				.param("codeHash", InvitationCodes.hash(code))
				.param("userId", CurrentUser.id())
				.query((rs, i) -> new Outcome(rs.getString("outcome"), rs.getObject("workspace_id", UUID.class),
						rs.getString("workspace_name"), rs.getString("role")))
				.single();
		return switch (result.outcome()) {
			case "ACCEPTED" -> new AcceptInvitationResponse(result.workspaceId(), result.name(), result.role());
			case "ALREADY_MEMBER" -> throw new MembershipConflictException("Você já participa deste workspace");
			case "FULL" -> throw new MembershipConflictException("O workspace atingiu o limite de membros");
			default -> throw new InvalidInvitationException();
		};
	}

	/** Os convites pendentes (não usados, não revogados e não expirados), do mais recente para o mais antigo. Sem o código. */
	@WorkspaceScoped(owner = true)
	@Transactional(readOnly = true)
	public List<InvitationResponse> list(UUID workspaceId) {
		return jdbc.sql(PENDING + "order by created_at desc, id\n")
				.param("workspaceId", workspaceId)
				.query(MAPPER)
				.list();
	}

	/** Revoga um convite que ainda não foi usado nem revogado: o código deixa de funcionar. Qualquer outro caso é "não encontrado". */
	@WorkspaceScoped(owner = true)
	@Transactional
	public void revoke(UUID workspaceId, UUID invitationId) {
		int revoked = jdbc.sql("""
				update invitations set revoked_at = now()
				where id = :id and workspace_id = :workspaceId and accepted_at is null and revoked_at is null
				""")
				.param("id", invitationId)
				.param("workspaceId", workspaceId)
				.update();
		if (revoked == 0) {
			throw new InvitationNotFoundException();
		}
	}

}
