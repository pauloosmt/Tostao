package com.tostao.tostao_api.common.error;

/**
 * Plano de parcelas inexistente, de outro workspace ou com identificador malformado (404): os casos são
 * indistinguíveis. O plano existe enquanto houver qualquer parcela do grupo, excluída ou não.
 */
public class InstallmentPlanNotFoundException extends RuntimeException {

	public InstallmentPlanNotFoundException() {
		super("Plano de parcelas não encontrado");
	}

}
