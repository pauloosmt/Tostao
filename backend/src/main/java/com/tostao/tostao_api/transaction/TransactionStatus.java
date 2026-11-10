package com.tostao.tostao_api.transaction;

/**
 * Estado de um lançamento. {@code CLEARED} (efetivado) conta no realizado; {@code PENDING} (pendente) é previsto
 * e nunca conta no saldo, no dashboard, nos orçamentos nem na fatura. Numa transferência, as duas pontas têm
 * sempre o mesmo estado.
 */
public enum TransactionStatus {
	CLEARED,
	PENDING
}
