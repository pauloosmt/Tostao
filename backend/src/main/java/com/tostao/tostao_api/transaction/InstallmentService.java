package com.tostao.tostao_api.transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.common.error.InstallmentPlanNotFoundException;
import com.tostao.tostao_api.transaction.dto.CancelInstallmentsResponse;
import com.tostao.tostao_api.transaction.dto.CreateInstallmentPurchaseRequest;
import com.tostao.tostao_api.transaction.dto.InstallmentPlanResponse;
import com.tostao.tostao_api.transaction.dto.TransactionResponse;
import com.tostao.tostao_api.workspace.CurrentUser;
import com.tostao.tostao_api.workspace.WorkspaceScoped;

/**
 * Compra parcelada. Não tem tabela própria: cada parcela é uma despesa comum de {@code transactions} e o plano é o
 * conjunto das despesas com o mesmo {@code installment_group_id}. Duas camadas de isolamento: {@link WorkspaceScoped}
 * (participação e papel) e o RLS cuidam do workspace, e toda consulta daqui também filtra explicitamente por
 * {@code workspace_id}.
 */
@Service
public class InstallmentService {

	private final JdbcClient jdbc;
	private final UsableRefs refs;

	public InstallmentService(JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.refs = new UsableRefs(jdbc);
	}

	/**
	 * Registra a compra: uma despesa por parcela, todas ou nenhuma (uma transação). Tudo o que pode falhar de forma
	 * previsível é conferido antes do primeiro {@code insert}: o estado, o total mínimo, a faixa de datas e, com
	 * travas em modo compartilhado, a conta e a categoria. Nenhuma exceção é capturada dentro da transação, então
	 * qualquer falha desfaz tudo. O grupo é gerado aqui, nunca vem do cliente.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public InstallmentPlanResponse create(UUID workspaceId, CreateInstallmentPurchaseRequest request) {
		TransactionStatus firstStatus = TransactionRules.parseStatus(request.firstInstallmentStatus(),
				"firstInstallmentStatus");
		int count = request.installmentCount();
		InstallmentRules.requireTotal(request.totalCents(), count);
		InstallmentRules.requireDatesInRange(request.firstOccurredOn(), count);

		refs.requireUsableAccount(workspaceId, request.accountId());
		refs.requireUsableCategory(workspaceId, request.categoryId(), TransactionType.EXPENSE);

		UUID group = UUID.randomUUID();
		long[] amounts = InstallmentRules.split(request.totalCents(), count);
		List<LocalDate> dates = InstallmentRules.dates(request.firstOccurredOn(), count);
		for (int i = 0; i < count; i++) {
			// a primeira nasce efetivada (ou como pedido); as seguintes, sempre pendentes
			TransactionStatus status = i == 0 && firstStatus != TransactionStatus.PENDING ? TransactionStatus.CLEARED
					: TransactionStatus.PENDING;
			insertInstallment(workspaceId, request, group, i + 1, count, amounts[i], dates.get(i), status);
		}
		return loadPlan(workspaceId, group);
	}

	private void insertInstallment(UUID workspaceId, CreateInstallmentPurchaseRequest request, UUID group, int number,
			int count, long amountCents, LocalDate date, TransactionStatus status) {
		jdbc.sql("""
				insert into transactions (workspace_id, account_id, category_id, type, amount_cents, occurred_on,
				                          description, notes, status, auto_clear, created_by, installment_group_id,
				                          installment_number, installment_count)
				values (:workspaceId, :accountId, :categoryId, 'EXPENSE', :amount, :occurredOn, :description, :notes,
				        :status, :autoClear, :createdBy, :groupId, :number, :count)
				""")
				.param("workspaceId", workspaceId)
				.param("accountId", request.accountId())
				.param("categoryId", request.categoryId())
				// despesa: valor negativo, como em toda despesa
				.param("amount", Math.negateExact(amountCents))
				.param("occurredOn", date)
				.param("description", InstallmentRules.describe(request.description(), number, count))
				.param("notes", request.notes())
				.param("status", status.name())
				// o marcador só vale para as parcelas pendentes: a primeira, se nasce efetivada, fica sem
				.param("autoClear", Boolean.TRUE.equals(request.autoClear()) && status == TransactionStatus.PENDING)
				.param("createdBy", CurrentUser.id())
				.param("groupId", group)
				.param("number", number)
				.param("count", count)
				.update();
	}

	/**
	 * Cancela o plano: exclui logicamente só as parcelas que ainda estão pendentes e vivas e informa quantas foram. As
	 * efetivadas e as já excluídas ficam como estão, então nenhum número realizado muda. O plano existe enquanto houver
	 * qualquer linha do grupo (excluída ou não): plano só com efetivadas, ou já cancelado, responde zero. As pendentes
	 * são travadas em ordem de número antes do {@code UPDATE} (dois cancelamentos simultâneos não se bloqueiam em
	 * ordens opostas) e a condição de estado é repetida nele: uma parcela efetivada ao mesmo tempo, depois de a trava
	 * ser conquistada, é relida como efetivada e fica de fora; uma parcela já excluída nunca é contada de novo.
	 */
	@WorkspaceScoped(write = true)
	@Transactional
	public CancelInstallmentsResponse cancel(UUID workspaceId, UUID groupId) {
		boolean exists = jdbc.sql("""
				select exists (select 1 from transactions
				               where workspace_id = :workspaceId and installment_group_id = :groupId)
				""")
				.param("workspaceId", workspaceId)
				.param("groupId", groupId)
				.query(Boolean.class)
				.single();
		if (!exists) {
			throw new InstallmentPlanNotFoundException();
		}

		List<UUID> pending = jdbc.sql("""
				select id from transactions
				where workspace_id = :workspaceId and installment_group_id = :groupId
				  and status = 'PENDING' and deleted_at is null
				order by installment_number
				for update
				""")
				.param("workspaceId", workspaceId)
				.param("groupId", groupId)
				.query(UUID.class)
				.list();
		if (pending.isEmpty()) {
			return new CancelInstallmentsResponse(0);
		}
		int cancelled = jdbc.sql("""
				update transactions set deleted_at = now(), updated_at = now()
				where workspace_id = :workspaceId and id in (:ids) and status = 'PENDING' and deleted_at is null
				""")
				.param("workspaceId", workspaceId)
				.param("ids", pending)
				.update();
		return new CancelInstallmentsResponse(cancelled);
	}

	/**
	 * O plano de um grupo. Lista e cabeçalho saem do mesmo instante ({@code REPEATABLE READ}, somente leitura).
	 * Plano inexistente, de outro workspace ou malformado é "não encontrado".
	 */
	@WorkspaceScoped
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public InstallmentPlanResponse plan(UUID workspaceId, UUID groupId) {
		return loadPlan(workspaceId, groupId);
	}

	/**
	 * Cabeçalho, parcelas e totais do plano. É privado de propósito: o registro também o usa, e chamar o método
	 * público por dentro do mesmo bean não passaria pelo proxy (nem pelo aspecto, nem pela transação).
	 *
	 * <p>
	 * O cabeçalho (descrição base, conta, categoria e total original) vem da parcela de menor número, <b>excluída
	 * ou não</b>: o plano continua existindo, e com o mesmo cabeçalho, depois de cancelado. Os totais são somados
	 * a partir da própria lista, então lista e totais nunca discordam.
	 */
	private InstallmentPlanResponse loadPlan(UUID workspaceId, UUID groupId) {
		record Head(UUID accountId, UUID categoryId, String description, int number, int count) {
		}
		Head head = jdbc.sql("""
				select account_id, category_id, description, installment_number, installment_count
				from transactions
				where workspace_id = :workspaceId and installment_group_id = :groupId
				order by installment_number
				limit 1
				""")
				.param("workspaceId", workspaceId)
				.param("groupId", groupId)
				.query((rs, i) -> new Head(rs.getObject("account_id", UUID.class),
						rs.getObject("category_id", UUID.class), rs.getString("description"),
						rs.getInt("installment_number"), rs.getInt("installment_count")))
				.optional()
				.orElseThrow(InstallmentPlanNotFoundException::new);

		List<TransactionResponse> installments = jdbc
				.sql(TransactionService.SELECT + "  and t.installment_group_id = :groupId\n  order by t.installment_number\n")
				.param("workspaceId", workspaceId)
				.param("groupId", groupId)
				.query(TransactionService.ROW_MAPPER)
				.list();

		long total = 0;
		long cleared = 0;
		long pending = 0;
		for (TransactionResponse installment : installments) {
			long cents = Math.negateExact(installment.amountCents());
			total = Math.addExact(total, cents);
			if (installment.status() == TransactionStatus.CLEARED) {
				cleared = Math.addExact(cleared, cents);
			}
			else {
				pending = Math.addExact(pending, cents);
			}
		}

		return new InstallmentPlanResponse(groupId,
				InstallmentRules.baseDescription(head.description(), head.number(), head.count()), head.count(),
				head.accountId(), head.categoryId(), total, cleared, pending, installments);
	}

}
