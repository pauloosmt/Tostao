package com.tostao.tostao_api.recurrence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.common.error.RecurrenceNotFoundException;
import com.tostao.tostao_api.dashboard.Period;
import com.tostao.tostao_api.recurrence.dto.CreateRecurrenceRequest;
import com.tostao.tostao_api.recurrence.dto.EndRecurrenceResponse;
import com.tostao.tostao_api.recurrence.dto.RecurrenceDetailResponse;
import com.tostao.tostao_api.recurrence.dto.RecurrenceResponse;
import com.tostao.tostao_api.transaction.TransactionService;
import com.tostao.tostao_api.transaction.TransactionStatus;
import com.tostao.tostao_api.transaction.TransactionType;
import com.tostao.tostao_api.transaction.UsableRefs;
import com.tostao.tostao_api.transaction.dto.TransactionResponse;
import com.tostao.tostao_api.workspace.CurrentUser;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/**
 * Recorrências. Duas camadas de isolamento: {@link WorkspaceScoped} (participação e papel) e o RLS cuidam do
 * workspace, e toda consulta daqui também filtra explicitamente por {@code workspace_id}. As ocorrências são
 * lançamentos comuns gerados pela função de banco {@code generate_recurrence_occurrences}, a mesma que a execução
 * automática usa: há uma única definição do calendário. A criação confere e trava conta e categoria antes de
 * gravar e não captura exceção alguma dentro da transação.
 */
@Service
public class RecurrenceService {

	/** Resumo com a próxima ocorrência pendente (de hoje em diante) calculada na própria consulta. */
	private static final String SUMMARY = """
			select r.id, r.account_id, r.destination_account_id, r.category_id, r.type, r.amount_cents, r.description, r.notes, r.frequency,
			       r.start_on, r.end_on, r.auto_clear, r.ended_at, r.created_by, r.created_at,
			       (select min(t.occurred_on) from transactions t
			        where t.workspace_id = r.workspace_id and t.recurrence_id = r.id and t.status = 'PENDING'
			          and t.deleted_at is null and t.occurred_on >= :today) as next_on
			from recurrences r
			where r.workspace_id = :workspaceId
			""";

	private static final RowMapper<RecurrenceResponse> SUMMARY_MAPPER = (rs, i) -> {
		OffsetDateTime endedAt = rs.getObject("ended_at", OffsetDateTime.class);
		return new RecurrenceResponse(
				rs.getObject("id", UUID.class),
				rs.getObject("account_id", UUID.class),
				rs.getObject("destination_account_id", UUID.class),
				rs.getObject("category_id", UUID.class),
				TransactionType.valueOf(rs.getString("type")),
				rs.getLong("amount_cents"),
				rs.getString("description"),
				rs.getString("notes"),
				RecurrenceFrequency.valueOf(rs.getString("frequency")),
				rs.getObject("start_on", LocalDate.class),
				rs.getObject("end_on", LocalDate.class),
				rs.getBoolean("auto_clear"),
				endedAt != null,
				endedAt == null ? null : endedAt.toInstant(),
				rs.getObject("next_on", LocalDate.class),
				rs.getObject("created_by", UUID.class),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	};

	private final JdbcClient jdbc;
	private final UsableRefs refs;

	public RecurrenceService(JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.refs = new UsableRefs(jdbc);
	}

	/** Hoje, no fuso do sistema (o mesmo do dashboard e da fatura). */
	private static LocalDate today() {
		return Period.today(Instant.now());
	}

	/**
	 * Cria a recorrência e gera as suas ocorrências até 12 meses depois de hoje, tudo ou nada. Tudo o que pode falhar
	 * de forma previsível é conferido antes do primeiro {@code insert}: tipo, a forma por tipo, início, fim e, com
	 * travas em modo compartilhado, as contas (origem e destino numa transferência) ou a conta e a categoria. A geração é a mesma função que a execução automática chama.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public RecurrenceDetailResponse create(UUID workspaceId, CreateRecurrenceRequest request) {
		LocalDate today = today();
		RecurrenceRules.requireType(request.type());
		RecurrenceRules.requireShape(request.type(), request.accountId(), request.destinationAccountId(), request.categoryId());
		RecurrenceRules.requireStart(request.startOn(), today);
		RecurrenceRules.requireEnd(request.startOn(), request.endOn());

		if (request.type() == TransactionType.TRANSFER) {
			// as duas contas, travadas juntas e em ordem de id, como na criação de uma transferência
			refs.requireUsableAccounts(workspaceId, request.accountId(), request.destinationAccountId());
		}
		else {
			refs.requireUsableAccount(workspaceId, request.accountId());
			refs.requireUsableCategory(workspaceId, request.categoryId(), request.type());
		}

		UUID id = jdbc.sql("""
				insert into recurrences (workspace_id, account_id, destination_account_id, category_id, type, amount_cents,
				                         description, notes, frequency, start_on, end_on, auto_clear, created_by)
				values (:workspaceId, :accountId, :destinationAccountId, :categoryId, :type, :amount, :description, :notes,
				        :frequency, :startOn, :endOn, :autoClear, :createdBy)
				returning id
				""")
				.param("workspaceId", workspaceId)
				.param("accountId", request.accountId())
				.param("destinationAccountId", request.destinationAccountId())
				.param("categoryId", request.categoryId())
				.param("type", request.type().name())
				.param("amount", request.amountCents())
				.param("description", request.description())
				.param("notes", request.notes())
				.param("frequency", request.frequency().name())
				.param("startOn", request.startOn())
				.param("endOn", request.endOn())
				.param("autoClear", Boolean.TRUE.equals(request.autoClear()))
				.param("createdBy", CurrentUser.id())
				.query(UUID.class)
				.single();

		jdbc.sql("select generate_recurrence_occurrences(cast(:today as date), cast(:id as uuid))")
				.param("today", today)
				.param("id", id)
				.query(Integer.class)
				.single();
		return loadDetail(workspaceId, id, today);
	}

	/**
	 * Encerra a recorrência: para de gerar e exclui logicamente só as ocorrências pendentes e vivas com data depois de
	 * hoje, mantendo as efetivadas e as pendentes que já venceram. Já encerrada, responde zero, sem efeito. A recorrência
	 * é travada ({@code FOR UPDATE}): a geração toma {@code FOR SHARE} nela, então uma geração em curso termina antes
	 * e este encerramento enxerga as ocorrências que ela criou, e uma geração que chega depois relê a linha, vê
	 * {@code ended_at} e a deixa de fora; em nenhuma ordem sobra ocorrência futura pendente. As ocorrências são
	 * travadas em ordem de número e a condição de estado é repetida no {@code UPDATE}: uma efetivação simultânea, manual
	 * ou automática, prevalece (uma efetivada nunca é desfeita).
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public EndRecurrenceResponse end(UUID workspaceId, UUID id) {
		LocalDate today = today();
		boolean alreadyEnded = jdbc.sql("""
				select ended_at is not null from recurrences
				where id = :id and workspace_id = :workspaceId
				for update
				""")
				.param("id", id)
				.param("workspaceId", workspaceId)
				.query(Boolean.class)
				.optional()
				.orElseThrow(RecurrenceNotFoundException::new);
		if (alreadyEnded) {
			return new EndRecurrenceResponse(0);
		}

		jdbc.sql("update recurrences set ended_at = now(), updated_at = now() where id = :id and workspace_id = :workspaceId")
				.param("id", id)
				.param("workspaceId", workspaceId)
				.update();

		List<UUID> future = jdbc.sql("""
				select id from transactions
				where workspace_id = :workspaceId and recurrence_id = :id and status = 'PENDING'
				  and deleted_at is null and occurred_on > :today
				order by occurrence_number
				for update
				""")
				.param("workspaceId", workspaceId)
				.param("id", id)
				.param("today", today)
				.query(UUID.class)
				.list();
		if (future.isEmpty()) {
			return new EndRecurrenceResponse(0);
		}
		int cancelled = jdbc.sql("""
				update transactions set deleted_at = now(), updated_at = now()
				where workspace_id = :workspaceId and id in (:ids) and status = 'PENDING' and deleted_at is null
				""")
				.param("workspaceId", workspaceId)
				.param("ids", future)
				.update();
		return new EndRecurrenceResponse(cancelled);
	}

	/** As recorrências do workspace, da mais recente para a mais antiga. */
	@WorkspaceScoped
	@Transactional(readOnly = true)
	public List<RecurrenceResponse> list(UUID workspaceId) {
		return jdbc.sql(SUMMARY + "order by r.created_at desc, r.id\n")
				.param("workspaceId", workspaceId)
				.param("today", today())
				.query(SUMMARY_MAPPER)
				.list();
	}

	/**
	 * Uma recorrência com as ocorrências não excluídas. Cabeçalho e lista saem do mesmo instante ({@code REPEATABLE
	 * READ}, somente leitura). Inexistente, de outro workspace ou malformada é "não encontrada".
	 */
	@WorkspaceScoped
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public RecurrenceDetailResponse get(UUID workspaceId, UUID id) {
		return loadDetail(workspaceId, id, today());
	}

	/**
	 * Cabeçalho, ocorrências e totais. É privado de propósito: a criação também o usa, e chamar o método público por
	 * dentro do mesmo bean não passaria pelo proxy (nem pelo aspecto, nem pela transação). Os totais são somados a
	 * partir da própria lista, então lista e totais nunca discordam.
	 */
	private RecurrenceDetailResponse loadDetail(UUID workspaceId, UUID id, LocalDate today) {
		RecurrenceResponse summary = jdbc.sql(SUMMARY + "  and r.id = :id\n")
				.param("workspaceId", workspaceId)
				.param("today", today)
				.param("id", id)
				.query(SUMMARY_MAPPER)
				.optional()
				.orElseThrow(RecurrenceNotFoundException::new);

		List<TransactionResponse> occurrences = jdbc
				.sql(TransactionService.SELECT + "  and t.recurrence_id = :id\n  order by t.occurrence_number\n")
				.param("workspaceId", workspaceId)
				.param("id", id)
				.query(TransactionService.ROW_MAPPER)
				.list();

		long total = 0;
		long cleared = 0;
		long pending = 0;
		for (TransactionResponse occurrence : occurrences) {
			if (summary.type() == TransactionType.TRANSFER && occurrence.amountCents() > 0) {
				// a ocorrência de transferência tem duas pontas: conta uma vez, pela saída, para o valor não dobrar
				continue;
			}
			long cents = Math.absExact(occurrence.amountCents());
			total = Math.addExact(total, cents);
			if (occurrence.status() == TransactionStatus.CLEARED) {
				cleared = Math.addExact(cleared, cents);
			}
			else {
				pending = Math.addExact(pending, cents);
			}
		}
		return RecurrenceDetailResponse.of(summary, total, cleared, pending, occurrences);
	}

}
