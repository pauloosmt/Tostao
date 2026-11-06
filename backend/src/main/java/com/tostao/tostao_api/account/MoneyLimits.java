package com.tostao.tostao_api.account;

/** Limites de valores monetários, sempre em centavos inteiros. */
public final class MoneyLimits {

	/** Módulo máximo de qualquer valor monetário de conta: R$ 10 bilhões. */
	public static final long MAX_CENTS = 1_000_000_000_000L;

	private MoneyLimits() {
	}

}
