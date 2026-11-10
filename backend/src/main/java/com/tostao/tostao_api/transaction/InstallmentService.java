package com.tostao.tostao_api.transaction;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.tostao.tostao_api.common.error.InstallmentPlanNotFoundException;
import com.tostao.tostao_api.transaction.dto.InstallmentPlanResponse;
import com.tostao.tostao_api.transaction.dto.TransactionResponse;
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

	public InstallmentService(JdbcClient jdbc) {
		this.jdbc = jdbc;
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
