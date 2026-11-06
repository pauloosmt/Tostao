package com.tostao.tostao_api.common.error;

/** Entrada bloqueada por falhas repetidas (429). */
public class TooManyAttemptsException extends RuntimeException {

	private final long retryAfterSeconds;

	public TooManyAttemptsException(long retryAfterSeconds) {
		super("Muitas tentativas de entrada. Tente novamente mais tarde");
		this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
	}

}
